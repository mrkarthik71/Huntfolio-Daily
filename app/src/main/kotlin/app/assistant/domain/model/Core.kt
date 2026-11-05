package app.assistant.domain.model

import java.time.Instant
import java.time.LocalDate

/** A Goal with a numeric target for each Period_Instance (Requirement 11). */
data class Goal(
    val id: GoalId,
    val name: String,
    val period: GoalPeriod,
    val startDate: LocalDate,
    val target: Quantity,
    val unit: String,
    val parentId: GoalId?,
    val createdAt: Instant,
    val archivedAt: Instant?,
) {
    val isActive: Boolean get() = archivedAt == null

    /** Glossary: unit "tasks" (ignoring letter case) counts completed linked Tasks (Requirement 11.5). */
    val countsTasks: Boolean get() = unit.trim().equals("tasks", ignoreCase = true)
}

/** A Goal with its progress in the current Period_Instance. */
data class GoalStatus(val goal: Goal, val instance: PeriodInstance, val progress: Quantity, val percent: Int, val daysBehind: Int) {
    val isBehind: Boolean get() = daysBehind >= 1
}

data class Task(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val dueDate: LocalDate?,
    val plannedDate: LocalDate?,
    val estimateMinutes: Int?,
    val goalId: GoalId?,
    val createdAt: Instant,
    val completedAt: Instant?,
)

enum class TimeBlockKind { PRIORITY, FOCUS, LEARNING }

data class TimeBlock(val id: TimeBlockId?, val taskId: TaskId?, val range: InstantRange, val kind: TimeBlockKind, val conflicting: Boolean = false)

/** A timed Calendar_Event as the Plan_Builder sees it (Phase 2 fills these). */
data class TimedEvent(val range: InstantRange, val title: String)

/** One unpaid Bill occurrence (Phase 3 fills these). */
data class BillOccurrence(val billId: BillId, val name: String, val amount: Paise, val dueDate: LocalDate)

/** The plan for one date (Requirement 14). Bills and Behind_Goals are computed when shown. */
data class DailyPlan(val date: LocalDate, val createdAt: Instant, val priorities: List<TaskId>, val timeBlocks: List<TimeBlock>)

/** Per-day counts used by the Streak_Rule (Requirement 17.3). */
data class DaySummary(val prioritiesTotal: Int, val prioritiesDone: Int, val tasksDone: Int)
