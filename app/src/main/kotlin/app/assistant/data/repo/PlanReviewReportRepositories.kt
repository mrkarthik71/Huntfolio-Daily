package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.DbProvider
import app.assistant.data.db.dao.DayCount
import app.assistant.data.db.entity.DailyPlanEntity
import app.assistant.data.db.entity.PlanPriorityEntity
import app.assistant.data.db.entity.ReportEntity
import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.engine.goals.ProgressMath
import app.assistant.domain.engine.plan.PlanBuilder
import app.assistant.domain.engine.plan.PlanInputs
import app.assistant.domain.engine.plan.PlanOverride
import app.assistant.domain.engine.review.ProgressReportData
import app.assistant.domain.engine.review.ReportCalculator
import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.review.ReviewEngine
import app.assistant.domain.engine.review.ReviewSummary
import app.assistant.domain.engine.review.WeekGoalRow
import app.assistant.domain.engine.review.WeeklyProgressReport
import app.assistant.domain.model.DailyPlan
import app.assistant.domain.model.DaySummary
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.Quantity
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import app.assistant.domain.model.TimedEvent
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.ReviewRepository
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.StoredPlan
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPlanRepository @Inject constructor(
    private val holder: DbProvider,
    private val settings: SettingsStore,
    private val goals: GoalRepository,
    private val time: TimeSource,
    private val calendar: CalendarRepository,
) : PlanRepository {
    private val db get() = holder.database()

    override fun planFor(date: LocalDate): Flow<StoredPlan?> {
        val d = date.toString()
        return combine(db.planDao().planFlow(d), db.taskDao().prioritiesFor(d), db.planDao().blocksFor(d)) { plan, tasks, blocks ->
            plan?.let {
                val priorities = tasks.map { t -> t.toDomain() }
                StoredPlan(
                    DailyPlan(date, Instant.ofEpochMilli(it.createdAt), priorities.map { t -> t.id }, blocks.map { b -> b.toDomain() }),
                    priorities,
                )
            }
        }
    }

    override suspend fun ensurePlanFor(date: LocalDate): StoredPlan {
        val existing = db.planDao().plan(date.toString())
        if (existing == null) createPlan(date)
        return checkNotNull(load(date))
    }

    private suspend fun createPlan(date: LocalDate) {
        val s = settings.current()
        val today = time.today()
        val behind = goals.statusesNow(today).filter { it.isBehind }.map { it.goal.id }.toSet()
        val tasks = db.taskDao().openTasksNow().map { it.toDomain() }
        val inputs = PlanInputs(date, time.now(), time.zone(), tasks, behind, timedEventsOn(date), s.workingHours)
        val plan = PlanBuilder.build(inputs)
        db.withTransaction {
            if (db.planDao().insertPlan(DailyPlanEntity(date.toString(), plan.createdAt.toEpochMilli())) == -1L) return@withTransaction
            plan.priorities.forEachIndexed { i, id -> db.planDao().putPriority(PlanPriorityEntity(date.toString(), i, id.value, userSet = false)) }
            plan.timeBlocks.forEach { db.planDao().insertBlock(it.toEntity(date)) }
        }
    }

    private suspend fun load(date: LocalDate): StoredPlan? {
        val d = date.toString()
        val plan = db.planDao().plan(d) ?: return null
        val rows = db.planDao().prioritiesNow(d)
        val byId = db.taskDao().run { rows.mapNotNull { get(it.taskId) } }.associateBy { it.id }
        val priorities = rows.mapNotNull { byId[it.taskId]?.toDomain() }
        val blocks = db.planDao().blocksNow(d).map { it.toDomain() }
        return StoredPlan(DailyPlan(date, Instant.ofEpochMilli(plan.createdAt), priorities.map { it.id }, blocks), priorities)
    }

    override suspend fun setPriorities(date: LocalDate, taskIds: List<TaskId>) {
        ensurePlanFor(date)
        val s = settings.current()
        val behind = goals.statusesNow(time.today()).filter { it.isBehind }.map { it.goal.id }.toSet()
        val tasks = db.taskDao().openTasksNow().map { it.toDomain() }
        // Only open Tasks can be Priorities, so a done, dropped or deleted Task never gets a stored Priority row.
        val openIds = tasks.map { it.id }.toSet()
        val ids = taskIds.distinct().filter { it in openIds }.take(PlanBuilder.MAX_PRIORITIES)
        val kept = db.planDao().blocksNow(date.toString()).map { it.toDomain() }.filter { it.kind != app.assistant.domain.model.TimeBlockKind.PRIORITY }
        val inputs = PlanInputs(date, time.now(), time.zone(), tasks, behind, timedEventsOn(date), s.workingHours, existingBlocks = kept)
        val rebuilt = PlanBuilder.build(inputs, PlanOverride.SetPriorities(ids))
        db.withTransaction {
            db.planDao().clearPriorities(date.toString())
            ids.forEachIndexed { i, id -> db.planDao().putPriority(PlanPriorityEntity(date.toString(), i, id.value, userSet = true)) }
            db.planDao().clearPriorityBlocks(date.toString())
            rebuilt.timeBlocks.forEach { db.planDao().insertBlock(it.toEntity(date)) }
        }
    }

    override suspend fun removePriority(date: LocalDate, taskId: TaskId) {
        db.withTransaction {
            db.planDao().removePriority(date.toString(), taskId.value)
            db.planDao().removeBlock(date.toString(), taskId.value)
        }
    }

    override fun streak(today: LocalDate): Flow<Int> = combine(
        db.taskDao().doneByDay(),
        db.taskDao().prioritiesDoneByDay(),
        db.taskDao().prioritiesTotalByDay(),
        settings.settings,
    ) { done, prioDone, prioTotal, s ->
        fun map(l: List<DayCount>) = l.associate { LocalDate.parse(it.date) to it.n }
        val d = map(done)
        val pd = map(prioDone)
        val pt = map(prioTotal)
        val days = (d.keys + pd.keys + pt.keys)
        val history = days.associateWith { DaySummary(pt[it] ?: 0, pd[it] ?: 0, d[it] ?: 0) }
        ProgressMath.streak(history, s.streakRule, today)
    }

    override suspend fun timeBlocks(date: LocalDate): List<TimeBlock> = db.planDao().blocksNow(date.toString()).map { it.toDomain() }

    override suspend fun addBlocks(date: LocalDate, blocks: List<TimeBlock>) {
        db.withTransaction { blocks.forEach { db.planDao().insertBlock(it.toEntity(date)) } }
    }

    override suspend fun moveBlock(id: TimeBlockId, range: InstantRange) {
        db.planDao().moveBlock(id.value, range.start.toEpochMilli(), range.end.toEpochMilli())
    }

    override suspend fun removeBlock(id: TimeBlockId) {
        db.planDao().removeBlockById(id.value)
    }

    /** Cached timed Calendar_Events for [date], so the Plan_Builder never places a block over one (Requirement 23.3). */
    private suspend fun timedEventsOn(date: LocalDate): List<TimedEvent> = calendar.eventsOn(date).first().timed.mapNotNull { f ->
        f.timed?.let { TimedEvent(it, f.title) }
    }
}

@Singleton
class RoomReviewRepository @Inject constructor(private val holder: DbProvider, private val settings: SettingsStore) : ReviewRepository {
    private val db get() = holder.database()

    override suspend fun finish(draft: ReviewDraft, at: Instant, today: LocalDate): ReviewSummary {
        val lastDay = settings.current().lastDayOfWeek
        return db.withTransaction {
            val listed = db.taskDao().plannedOnNow(draft.reviewDate.toString())
            val summary = ReviewEngine.summarise(listed.map { it.toDomain() }, draft)
            val tomorrow = draft.reviewDate.plusDays(1)
            for (t in listed.filter { it.status == TaskStatus.OPEN.name }) {
                when (draft.choices[TaskId(t.id)]) {
                    ReviewChoice.DONE -> TaskOps.markDone(db, t.id, at, draft.reviewDate, lastDay)
                    ReviewChoice.MOVE_TO_TOMORROW -> TaskOps.moveTask(db, t, tomorrow, today, alsoLeave = null)
                    ReviewChoice.DROP -> db.taskDao().setStatus(t.id, TaskStatus.DROPPED.name, null, null)
                    null -> Unit
                }
            }
            summary
        }
    }
}

@Singleton
class RoomReportRepository @Inject constructor(private val holder: DbProvider, private val settings: SettingsStore, private val time: TimeSource) :
    ReportRepository {
    private val db get() = holder.database()

    override suspend fun ensureReportsUpTo(today: LocalDate) {
        val firstTask = db.taskDao().firstTaskCreatedAt() ?: return
        val firstEver = Instant.ofEpochMilli(firstTask).atZone(time.zone()).toLocalDate()
        val lastDay = settings.current().lastDayOfWeek
        val since = db.reportDao().lastPeriod(KIND)?.let { LocalDate.parse(it).plusDays(6) }
        val weeks = ReportCalculator.endedWeeks(since, today, lastDay, firstEver)
        val now = time.now().toEpochMilli()
        db.withTransaction { weeks.forEach { db.reportDao().insert(ReportEntity(KIND, it.first.toString(), now)) } }
    }

    override suspend fun weeklyProgress(week: PeriodInstance): WeeklyProgressReport {
        val zone = time.zone()
        val progress = db.goalDao().allProgressNow().associate { (it.goalId to it.periodFirstDay) to it.progressHundredths }
        val rows = db.goalDao().all()
            .filter { it.period == GoalPeriod.WEEK.name && !LocalDate.parse(it.startDate).isAfter(week.last) }
            .filter { g -> g.archivedAt == null || !Instant.ofEpochMilli(g.archivedAt).atZone(zone).toLocalDate().isBefore(week.first) }
            .map { g -> WeekGoalRow(g.name, Quantity(g.targetHundredths), Quantity(progress[g.id to week.first.toString()] ?: 0L), g.unit) }
        val moves = db.taskDao().movesBetween(week.first.toString(), week.last.toString())
            .associate { TaskId(it.taskId) to (it.title to it.moves) }
        return ReportCalculator.weeklyProgress(week, ProgressReportData(rows, moves))
    }

    override fun latestWeek(): Flow<PeriodInstance?> = db.reportDao().latest(KIND).map { r ->
        r?.let { Periods.weekEndingOn(LocalDate.parse(it.periodFirstDay).plusDays(6)) }
    }

    private companion object {
        const val KIND = "WEEKLY_PROGRESS"
    }
}
