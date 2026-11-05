package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.GoalEntity
import app.assistant.data.db.entity.GoalProgressEntity
import app.assistant.data.db.entity.TaskEntity
import app.assistant.data.db.entity.TaskMoveEntity
import app.assistant.domain.engine.goals.GoalField
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.GoalValidator
import app.assistant.domain.engine.goals.LinkError
import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.engine.goals.ProgressMath
import app.assistant.domain.engine.goals.QuantityParser
import app.assistant.domain.engine.tasks.MoveError
import app.assistant.domain.engine.tasks.TaskField
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.engine.tasks.TaskValidator
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalStatus
import app.assistant.domain.model.Quantity
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Shared write operations that run inside a caller's Room transaction. */
internal object TaskOps {
    /** Adds [amount] to the Goal's progress for the Period_Instance containing [on]. */
    suspend fun addProgress(db: AppDatabase, goal: GoalEntity, amount: Quantity, on: LocalDate, lastDay: DayOfWeek) {
        val instance = Periods.instanceContaining(app.assistant.domain.model.GoalPeriod.valueOf(goal.period), on, lastDay)
        val key = instance.first.toString()
        val current = db.goalDao().progressOf(goal.id, key) ?: 0L
        db.goalDao().putProgress(GoalProgressEntity(goal.id, key, Math.addExact(current, amount.hundredths)))
    }

    /** Marks an open Task done on [on]; a linked active "tasks" Goal gains 1 (Requirement 11.5). */
    suspend fun markDone(db: AppDatabase, id: Long, at: Instant, on: LocalDate, lastDay: DayOfWeek) {
        val task = db.taskDao().get(id) ?: return
        if (task.status != TaskStatus.OPEN.name) return
        db.taskDao().setStatus(id, TaskStatus.DONE.name, at.toEpochMilli(), on.toString())
        val goal = task.goalId?.let { db.goalDao().get(it) } ?: return
        if (goal.archivedAt == null && goal.unit.trim().equals("tasks", ignoreCase = true)) {
            addProgress(db, goal, Quantity.ONE, on, lastDay)
        }
    }

    /** Records a move and removes the Task from the Priorities and Time_Blocks of the dates it leaves. */
    suspend fun moveTask(db: AppDatabase, task: TaskEntity, to: LocalDate, movedOn: LocalDate, alsoLeave: LocalDate?) {
        db.taskDao().insertMove(TaskMoveEntity(taskId = task.id, fromDate = task.plannedDate, toDate = to.toString(), movedOn = movedOn.toString()))
        db.taskDao().setPlanned(task.id, to.toString())
        // A date the Task is moving onto is not one it leaves (a Task with no Planned_Date moved to today stays in today's plan).
        val leaving = listOfNotNull(task.plannedDate, alsoLeave?.toString()).distinct().filter { it != to.toString() }
        for (d in leaving) {
            db.planDao().removePriority(d, task.id)
            db.planDao().removeBlock(d, task.id)
        }
    }
}

@Singleton
class RoomGoalRepository @Inject constructor(private val holder: DbProvider, private val settings: SettingsStore, private val time: TimeSource) :
    GoalRepository {
    private val db get() = holder.database()

    override fun statuses(today: LocalDate): Flow<List<GoalStatus>> =
        combine(db.goalDao().activeGoals(), db.goalDao().allProgress(), settings.settings) { goals, progress, s ->
            statusesOf(goals, progress, s.lastDayOfWeek, today)
        }

    override suspend fun statusesNow(today: LocalDate): List<GoalStatus> =
        statusesOf(db.goalDao().activeGoalsNow(), db.goalDao().allProgressNow(), settings.current().lastDayOfWeek, today)

    private fun statusesOf(goals: List<GoalEntity>, progress: List<GoalProgressEntity>, lastDay: DayOfWeek, today: LocalDate): List<GoalStatus> {
        val byKey = progress.associate { (it.goalId to it.periodFirstDay) to it.progressHundredths }
        return goals.map { e ->
            val goal = e.toDomain()
            val instance = Periods.instanceContaining(goal.period, today, lastDay)
            val p = Quantity(byKey[goal.id.value to instance.first.toString()] ?: 0L)
            GoalStatus(goal, instance, p, ProgressMath.percent(p, goal.target), ProgressMath.daysBehind(goal, instance, p, today))
        }
    }

    override suspend fun get(id: GoalId): Goal? = db.goalDao().get(id.value)?.toDomain()

    override suspend fun save(input: GoalInput, existing: GoalId?): Outcome<GoalId, FieldErrors<GoalField>> {
        val errors = GoalValidator.validate(input)
        if (!errors.isValid) return Outcome.Err(errors)
        val target = (QuantityParser.parse(input.targetText) as Outcome.Ok).value
        return db.withTransaction {
            val id = if (existing == null) {
                db.goalDao().insert(
                    GoalEntity(
                        name = input.name.trim(),
                        period = input.period!!.name,
                        startDate = input.startDate!!.toString(),
                        targetHundredths = target.hundredths,
                        unit = input.unit.trim(),
                        parentGoalId = null,
                        createdAt = time.now().toEpochMilli(),
                        archivedAt = null,
                    ),
                )
            } else {
                val old =
                    db.goalDao().get(existing.value) ?: return@withTransaction Outcome.Err(FieldErrors(mapOf(GoalField.NAME to ValidationError.NOT_ALLOWED)))
                db.goalDao().update(
                    old.copy(
                        name = input.name.trim(),
                        period = input.period!!.name,
                        startDate = input.startDate!!.toString(),
                        targetHundredths = target.hundredths,
                        unit = input.unit.trim(),
                    ),
                )
                existing.value
            }
            Outcome.Ok(GoalId(id))
        }
    }

    override suspend fun link(child: GoalId, parent: GoalId?): Outcome<Unit, LinkError> {
        if (parent == null) {
            db.goalDao().setParent(child.value, null)
            return Outcome.Ok(Unit)
        }
        val c = db.goalDao().get(child.value)?.toDomain() ?: return Outcome.Err(LinkError.NOT_FOUND)
        val p = db.goalDao().get(parent.value)?.toDomain() ?: return Outcome.Err(LinkError.NOT_FOUND)
        return when (val check = GoalValidator.validateLink(c, p)) {
            is Outcome.Err -> check
            is Outcome.Ok -> {
                db.goalDao().setParent(child.value, parent.value)
                Outcome.Ok(Unit)
            }
        }
    }

    override suspend fun logProgress(goal: GoalId, amountText: String, on: LocalDate): Outcome<Unit, ValidationError> {
        val amount = when (val q = GoalValidator.validateProgress(amountText)) {
            is Outcome.Err -> return q
            is Outcome.Ok -> q.value
        }
        val lastDay = settings.current().lastDayOfWeek
        return db.withTransaction {
            val g = db.goalDao().get(goal.value)
            if (g == null || g.archivedAt != null) {
                Outcome.Err(ValidationError.NOT_ALLOWED)
            } else {
                TaskOps.addProgress(db, g, amount, on, lastDay)
                Outcome.Ok(Unit)
            }
        }
    }

    override suspend fun archive(goal: GoalId, at: Instant) {
        db.goalDao().archive(goal.value, at.toEpochMilli())
    }
}

@Singleton
class RoomTaskRepository @Inject constructor(private val holder: DbProvider, private val settings: SettingsStore) : TaskRepository {
    private val db get() = holder.database()

    override fun openTasks(): Flow<List<Task>> = db.taskDao().openTasks().map { l -> l.map { it.toDomain() } }

    override fun plannedOn(date: LocalDate): Flow<List<Task>> = db.taskDao().plannedOn(date.toString()).map { l -> l.map { it.toDomain() } }

    override suspend fun get(id: TaskId): Task? = db.taskDao().get(id.value)?.toDomain()

    override suspend fun save(input: TaskInput, existing: TaskId?, now: Instant, today: LocalDate): Outcome<TaskId, FieldErrors<TaskField>> {
        val errors = TaskValidator.validate(input)
        if (!errors.isValid) return Outcome.Err(errors)
        val estimate = TaskValidator.estimateMinutes(input)
        return db.withTransaction {
            if (existing == null) {
                val id = db.taskDao().insert(
                    TaskEntity(
                        title = input.title.trim(),
                        status = TaskStatus.OPEN.name,
                        dueDate = input.dueDate?.toString(),
                        plannedDate = input.plannedDate?.toString(),
                        estimateMinutes = estimate,
                        goalId = input.goalId?.value,
                        createdAt = now.toEpochMilli(),
                        completedAt = null,
                        completedDate = null,
                        sourceRef = null,
                    ),
                )
                Outcome.Ok(TaskId(id))
            } else {
                val old =
                    db.taskDao().get(existing.value) ?: return@withTransaction Outcome.Err(FieldErrors(mapOf(TaskField.TITLE to ValidationError.NOT_ALLOWED)))
                db.taskDao().update(
                    old.copy(
                        title = input.title.trim(),
                        dueDate = input.dueDate?.toString(),
                        estimateMinutes = estimate,
                        goalId = input.goalId?.value,
                    ),
                )
                // A changed Planned_Date is a move, so it is recorded and leaves the old plan.
                val newPlanned = input.plannedDate
                if (newPlanned != null && newPlanned.toString() != old.plannedDate && !newPlanned.isBefore(today)) {
                    TaskOps.moveTask(db, old, newPlanned, today, alsoLeave = today)
                }
                Outcome.Ok(existing)
            }
        }
    }

    override suspend fun quickAdd(raw: String, now: Instant, today: LocalDate): Outcome<TaskId, ValidationError> {
        val title = when (val n = TaskValidator.normaliseQuickAdd(raw)) {
            is Outcome.Err -> return n
            is Outcome.Ok -> n.value
        }
        val id = db.taskDao().insert(
            TaskEntity(
                title = title,
                status = TaskStatus.OPEN.name,
                dueDate = null,
                plannedDate = today.toString(),
                estimateMinutes = null,
                goalId = null,
                createdAt = now.toEpochMilli(),
                completedAt = null,
                completedDate = null,
                sourceRef = null,
            ),
        )
        return Outcome.Ok(TaskId(id))
    }

    override suspend fun markDone(id: TaskId, at: Instant, on: LocalDate) {
        val lastDay = settings.current().lastDayOfWeek
        db.withTransaction { TaskOps.markDone(db, id.value, at, on, lastDay) }
    }

    override suspend fun move(id: TaskId, to: LocalDate, today: LocalDate): Outcome<Unit, MoveError> = db.withTransaction {
        val task = db.taskDao().get(id.value) ?: return@withTransaction Outcome.Err(MoveError.NOT_FOUND)
        val from = task.plannedDate?.let(LocalDate::parse)
        when (val check = TaskValidator.checkMove(from, to, today)) {
            is Outcome.Err -> check
            is Outcome.Ok -> {
                TaskOps.moveTask(db, task, to, today, alsoLeave = today)
                Outcome.Ok(Unit)
            }
        }
    }

    override suspend fun drop(id: TaskId) {
        db.withTransaction {
            val t = db.taskDao().get(id.value) ?: return@withTransaction
            if (t.status == TaskStatus.OPEN.name) db.taskDao().setStatus(id.value, TaskStatus.DROPPED.name, null, null)
        }
    }

    override suspend fun delete(id: TaskId) {
        db.withTransaction {
            db.planDao().removePrioritiesOfTask(id.value)
            db.planDao().removeBlocksOfTask(id.value)
            db.taskDao().delete(id.value)
        }
    }
}
