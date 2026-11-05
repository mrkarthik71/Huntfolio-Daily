package app.assistant.domain.port

import app.assistant.domain.engine.goals.GoalField
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.LinkError
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.review.ReviewSummary
import app.assistant.domain.engine.review.WeeklyProgressReport
import app.assistant.domain.engine.tasks.MoveError
import app.assistant.domain.engine.tasks.TaskField
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
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

interface GoalRepository {
    /** Active Goals with progress in the Period_Instance that contains [today]. */
    fun statuses(today: LocalDate): Flow<List<GoalStatus>>

    suspend fun statusesNow(today: LocalDate): List<GoalStatus>

    suspend fun get(id: GoalId): Goal?

    /** Creates a Goal, or updates [existing]; an invalid form changes nothing (Requirement 11.2). */
    suspend fun save(input: GoalInput, existing: GoalId? = null): Outcome<GoalId, FieldErrors<GoalField>>

    suspend fun link(child: GoalId, parent: GoalId?): Outcome<Unit, LinkError>

    /** Adds [amountText] to the Period_Instance containing [on] (Requirement 11.4, 11.8). */
    suspend fun logProgress(goal: GoalId, amountText: String, on: LocalDate): Outcome<Unit, ValidationError>

    suspend fun archive(goal: GoalId, at: Instant)
}

interface TaskRepository {
    fun openTasks(): Flow<List<Task>>

    /** Every Task planned for [date], whatever its status. */
    fun plannedOn(date: LocalDate): Flow<List<Task>>

    suspend fun get(id: TaskId): Task?

    suspend fun save(input: TaskInput, existing: TaskId? = null, now: Instant, today: LocalDate): Outcome<TaskId, FieldErrors<TaskField>>

    suspend fun quickAdd(raw: String, now: Instant, today: LocalDate): Outcome<TaskId, ValidationError>

    /** Marks done and adds Goal "tasks" progress in the same transaction (Requirement 11.5). */
    suspend fun markDone(id: TaskId, at: Instant, on: LocalDate)

    suspend fun move(id: TaskId, to: LocalDate, today: LocalDate): Outcome<Unit, MoveError>

    suspend fun drop(id: TaskId)

    /** Removes the Task and its Time_Blocks; Goal progress already recorded is kept (Requirement 12.5). */
    suspend fun delete(id: TaskId)
}

/** The stored plan for one date with its Priority Tasks resolved. */
data class StoredPlan(val plan: DailyPlan, val priorities: List<Task>)

interface PlanRepository {
    fun planFor(date: LocalDate): Flow<StoredPlan?>

    /** Builds and stores the plan if none exists for [date]; never overwrites an existing one (Requirement 14.7). */
    suspend fun ensurePlanFor(date: LocalDate): StoredPlan

    /** Replaces the Priorities with the User's order; Time_Blocks are rebuilt for the new set (Requirement 14.7). */
    suspend fun setPriorities(date: LocalDate, taskIds: List<TaskId>)

    suspend fun removePriority(date: LocalDate, taskId: TaskId)

    /** Streak from stored history under the current Streak_Rule (Requirement 17.3, 17.4). */
    fun streak(today: LocalDate): Flow<Int>

    suspend fun timeBlocks(date: LocalDate): List<TimeBlock>

    /** Stores accepted Focus_Blocks for [date] (Requirement 25.4). */
    suspend fun addBlocks(date: LocalDate, blocks: List<TimeBlock>)

    /** Moves one Time_Block to [range] after the User accepts a conflict suggestion (Requirement 23.6). */
    suspend fun moveBlock(id: TimeBlockId, range: InstantRange)

    /** Removes one Time_Block after the User chooses to drop a conflicting block. */
    suspend fun removeBlock(id: TimeBlockId)
}

interface ReviewRepository {
    /** Applies every choice in one transaction; nothing is written before this call (Requirement 16.7). */
    suspend fun finish(draft: ReviewDraft, at: Instant, today: LocalDate): ReviewSummary
}

interface ReportRepository {
    /** Creates the weekly progress report for every week that ended since the last run (Requirement 17.5, 17.6). */
    suspend fun ensureReportsUpTo(today: LocalDate)

    /** Content is calculated from stored data for the week, so late equals on-time. */
    suspend fun weeklyProgress(week: PeriodInstance): WeeklyProgressReport

    /** The most recent week that has a report, if any. */
    fun latestWeek(): Flow<PeriodInstance?>
}
