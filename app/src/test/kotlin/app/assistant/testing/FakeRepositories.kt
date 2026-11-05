package app.assistant.testing

import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.LinkError
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.review.ReviewSummary
import app.assistant.domain.engine.tasks.MoveError
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.DailyPlan
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalStatus
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.ReviewRepository
import app.assistant.domain.port.StoredPlan
import app.assistant.domain.port.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory fakes for the Phase 1 repositories, used by the Compose UI tests and the widget tests
 * so a screen or an ActionCallback can run without the encrypted Room graph. They implement just
 * enough of each port to exercise the UI states the tests assert.
 */
class FakeTaskRepository(initial: List<Task> = emptyList()) : TaskRepository {
    val tasks = MutableStateFlow(initial)
    val markedDone = mutableListOf<TaskId>()
    private var nextId = (initial.maxOfOrNull { it.id.value } ?: 0L) + 1

    override fun openTasks(): Flow<List<Task>> = tasks.map { list -> list.filter { it.status == TaskStatus.OPEN } }

    override fun plannedOn(date: LocalDate): Flow<List<Task>> = tasks.map { list -> list.filter { it.plannedDate == date } }

    override suspend fun get(id: TaskId): Task? = tasks.value.firstOrNull { it.id == id }

    override suspend fun save(
        input: TaskInput,
        existing: TaskId?,
        now: Instant,
        today: LocalDate,
    ): Outcome<TaskId, FieldErrors<app.assistant.domain.engine.tasks.TaskField>> {
        val id = existing ?: TaskId(nextId++)
        return Outcome.Ok(id)
    }

    override suspend fun quickAdd(raw: String, now: Instant, today: LocalDate): Outcome<TaskId, ValidationError> {
        val title = raw.trim()
        if (title.isEmpty()) return Outcome.Err(ValidationError.EMPTY)
        val id = TaskId(nextId++)
        tasks.value = tasks.value + Task(id, title, TaskStatus.OPEN, null, today, null, null, now, null)
        return Outcome.Ok(id)
    }

    override suspend fun markDone(id: TaskId, at: Instant, on: LocalDate) {
        markedDone += id
        tasks.value = tasks.value.map { if (it.id == id) it.copy(status = TaskStatus.DONE, completedAt = at) else it }
    }

    override suspend fun move(id: TaskId, to: LocalDate, today: LocalDate): Outcome<Unit, MoveError> = Outcome.Ok(Unit)

    override suspend fun drop(id: TaskId) {
        tasks.value = tasks.value.filterNot { it.id == id }
    }

    override suspend fun delete(id: TaskId) {
        tasks.value = tasks.value.filterNot { it.id == id }
    }
}

class FakePlanRepository(private val taskRepo: FakeTaskRepository, private val priorityIds: List<TaskId> = emptyList()) : PlanRepository {
    override fun planFor(date: LocalDate): Flow<StoredPlan?> = taskRepo.tasks.map { all ->
        val priorities = all.filter { it.id in priorityIds }
        StoredPlan(DailyPlan(date, Instant.EPOCH, priorities.map { it.id }, emptyList()), priorities)
    }

    override suspend fun ensurePlanFor(date: LocalDate): StoredPlan {
        val all = taskRepo.tasks.value
        val priorities = all.filter { it.id in priorityIds }
        return StoredPlan(DailyPlan(date, Instant.EPOCH, priorities.map { it.id }, emptyList()), priorities)
    }

    override suspend fun setPriorities(date: LocalDate, taskIds: List<TaskId>) = Unit

    override suspend fun removePriority(date: LocalDate, taskId: TaskId) = Unit

    override fun streak(today: LocalDate): Flow<Int> = MutableStateFlow(0)

    override suspend fun timeBlocks(date: LocalDate): List<TimeBlock> = emptyList()

    override suspend fun addBlocks(date: LocalDate, blocks: List<TimeBlock>) = Unit

    override suspend fun moveBlock(id: TimeBlockId, range: InstantRange) = Unit

    override suspend fun removeBlock(id: TimeBlockId) = Unit
}

class FakeGoalRepository(private val statuses: List<GoalStatus> = emptyList()) : GoalRepository {
    override fun statuses(today: LocalDate): Flow<List<GoalStatus>> = MutableStateFlow(statuses)

    override suspend fun statusesNow(today: LocalDate): List<GoalStatus> = statuses

    override suspend fun get(id: GoalId): Goal? = null

    override suspend fun save(input: GoalInput, existing: GoalId?): Outcome<GoalId, FieldErrors<app.assistant.domain.engine.goals.GoalField>> =
        Outcome.Ok(GoalId(1))

    override suspend fun link(child: GoalId, parent: GoalId?): Outcome<Unit, LinkError> = Outcome.Ok(Unit)

    override suspend fun logProgress(goal: GoalId, amountText: String, on: LocalDate): Outcome<Unit, ValidationError> = Outcome.Ok(Unit)

    override suspend fun archive(goal: GoalId, at: Instant) = Unit
}

class FakeReportRepository : ReportRepository {
    override suspend fun ensureReportsUpTo(today: LocalDate) = Unit

    override suspend fun weeklyProgress(week: PeriodInstance): app.assistant.domain.engine.review.WeeklyProgressReport =
        app.assistant.domain.engine.review.WeeklyProgressReport(week, emptyList(), emptyList())

    override fun latestWeek(): Flow<PeriodInstance?> = MutableStateFlow(null)
}

class FakeReviewRepository : ReviewRepository {
    var finishCalled = false
        private set

    override suspend fun finish(draft: ReviewDraft, at: Instant, today: LocalDate): ReviewSummary {
        finishCalled = true
        return ReviewSummary(0, 0, 0, 0)
    }
}
