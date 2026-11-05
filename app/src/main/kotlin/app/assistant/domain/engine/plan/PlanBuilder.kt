package app.assistant.domain.engine.plan

import app.assistant.domain.model.DailyPlan
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockKind
import app.assistant.domain.model.TimedEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PlanInputs(
    val date: LocalDate,
    val createdAt: Instant,
    val zone: ZoneId,
    val openTasks: List<Task>,
    val behindGoalIds: Set<GoalId>,
    val timedEvents: List<TimedEvent>,
    val workingHours: LocalTimeRange,
    /** Time_Blocks already on the date that the build must not overlap (for example focus blocks). */
    val existingBlocks: List<TimeBlock> = emptyList(),
)

/** A User change to the plan for one date (Requirement 14.7), applied after every rebuild. */
sealed interface PlanOverride {
    /** The User's full Priority order for the date. */
    data class SetPriorities(val taskIds: List<TaskId>) : PlanOverride
}

/** Pure, deterministic rule-based Plan_Builder (Requirements 14.2 to 14.5, 14.10, 23.2, 23.3). */
object PlanBuilder {
    const val MAX_PRIORITIES = 3

    /**
     * Ranking keys in order: overdue first; earlier due date (no due date last); linked to a
     * Behind_Goal first; earlier creation time; then Task ID so equal keys still have one order.
     */
    fun comparator(today: LocalDate, behindGoalIds: Set<GoalId>): Comparator<Task> =
        compareBy<Task> { t -> if (t.dueDate != null && t.dueDate.isBefore(today)) 0 else 1 }
            .thenBy { t -> t.dueDate?.toEpochDay() ?: Long.MAX_VALUE }
            .thenBy { t -> if (t.goalId != null && t.goalId in behindGoalIds) 0 else 1 }
            .thenBy { t -> t.createdAt }
            .thenBy { t -> t.id.value }

    /** Candidates: open Tasks with no Planned_Date or one not later than [today] (Requirement 14.2). */
    fun candidates(tasks: List<Task>, today: LocalDate): List<Task> =
        tasks.filter { it.status == TaskStatus.OPEN && (it.plannedDate == null || !it.plannedDate.isAfter(today)) }

    fun rank(tasks: List<Task>, today: LocalDate, behindGoalIds: Set<GoalId>): List<Task> =
        candidates(tasks, today).sortedWith(comparator(today, behindGoalIds))

    fun build(inputs: PlanInputs, override: PlanOverride.SetPriorities? = null): DailyPlan {
        val ranked = rank(inputs.openTasks, inputs.date, inputs.behindGoalIds)
        val byId = inputs.openTasks.associateBy { it.id }
        val priorities: List<Task> = if (override != null) {
            // Keep the User's order; drop Tasks that are no longer open (done, dropped or deleted).
            override.taskIds.mapNotNull { id -> byId[id]?.takeIf { it.status == TaskStatus.OPEN } }.distinctBy { it.id }
        } else {
            ranked.take(MAX_PRIORITIES)
        }
        val blocks = placeBlocks(priorities, inputs)
        return DailyPlan(inputs.date, inputs.createdAt, priorities.map { it.id }, blocks)
    }

    /** Working_Hours of [date] in [zone], clipped to start no earlier than [notBefore]. */
    fun workingWindow(date: LocalDate, zone: ZoneId, hours: LocalTimeRange, notBefore: Instant): InstantRange? {
        if (hours.isEmpty || hours.spansMidnight) return null
        val start = maxOf(date.atTime(hours.start).atZone(zone).toInstant(), notBefore)
        val end = date.atTime(hours.end).atZone(zone).toInstant()
        return if (start < end) InstantRange(start, end) else null
    }

    /** Busy time on [date]: events and existing blocks, clipped to the date, merged, sorted (Requirement 23.2). */
    fun busyIntervals(date: LocalDate, zone: ZoneId, events: List<TimedEvent>, blocks: List<TimeBlock>): List<InstantRange> {
        val day = InstantRange(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        val ranges = (events.map { it.range } + blocks.map { it.range })
            .filterNot { it.isEmpty }
            .mapNotNull { it.intersect(day) }
            .sortedWith(compareBy({ it.start }, { it.end }))
        return merge(ranges)
    }

    fun merge(sorted: List<InstantRange>): List<InstantRange> {
        val out = ArrayList<InstantRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && !r.start.isAfter(last.end)) {
                out[out.size - 1] = InstantRange(last.start, maxOf(last.end, r.end))
            } else {
                out += r
            }
        }
        return out
    }

    /** [window] minus [busy] as half-open ranges. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun freeIntervals(window: InstantRange, busy: List<InstantRange>): List<InstantRange> {
        val free = ArrayList<InstantRange>()
        var cursor = window.start
        for (b in busy) {
            if (!b.end.isAfter(cursor)) continue
            if (!b.start.isBefore(window.end)) break
            if (b.start.isAfter(cursor)) free += InstantRange(cursor, minOf(b.start, window.end))
            cursor = maxOf(cursor, b.end)
            if (!cursor.isBefore(window.end)) break
        }
        if (cursor.isBefore(window.end)) free += InstantRange(cursor, window.end)
        return free
    }

    /** First fit in rank order; a Priority that fits nowhere keeps no Time_Block (Requirement 14.10). */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun placeBlocks(priorities: List<Task>, inputs: PlanInputs): List<TimeBlock> {
        val window = workingWindow(inputs.date, inputs.zone, inputs.workingHours, inputs.createdAt) ?: return emptyList()
        var free = freeIntervals(window, busyIntervals(inputs.date, inputs.zone, inputs.timedEvents, inputs.existingBlocks))
        val placed = ArrayList<TimeBlock>()
        for (task in priorities) {
            val minutes = task.estimateMinutes ?: continue
            val length = Duration.ofMinutes(minutes.toLong())
            val slot = free.firstOrNull { it.duration >= length } ?: continue
            val block = InstantRange(slot.start, slot.start.plus(length))
            placed += TimeBlock(id = null, taskId = task.id, range = block, kind = TimeBlockKind.PRIORITY)
            free = free.flatMap { f -> if (f == slot) listOfNotNull(InstantRange(block.end, f.end).takeUnless { it.isEmpty }) else listOf(f) }
        }
        return placed
    }
}
