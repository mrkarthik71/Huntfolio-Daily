package app.assistant.domain.engine.calendar

import app.assistant.domain.engine.plan.PlanBuilder
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockKind
import app.assistant.domain.model.TimedEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A set of open Tasks proposed for one Free_Gap, in proposal order (Requirement 25.2). */
data class FocusBlockProposal(val gap: InstantRange, val taskIds: List<TaskId>, val tasks: List<Task>)

/**
 * Finds Free_Gaps within Working_Hours and fits open Tasks into them (Requirement 25). Pure: the
 * caller supplies `now` and `zone`. Only the part of a gap after `now` counts, and a gap shorter
 * than Min_Focus_Length is dropped. A proposal is greedy first-fit in ranking order, each Task used
 * at most once across the date's proposals, never exceeding the gap length (invariant 25.3).
 */
object GapFinder {

    fun freeGaps(
        date: LocalDate,
        now: Instant,
        zone: ZoneId,
        workingHours: LocalTimeRange,
        events: List<TimedEvent>,
        blocks: List<TimeBlock>,
        minFocus: Duration,
    ): List<InstantRange> {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        if (workingHours.isEmpty) return emptyList()
        // Working_Hours as an instant window on the date (midnight-spanning windows are clipped to the day).
        val whStart = date.atTime(workingHours.start).atZone(zone).toInstant().coerceIn(dayStart, dayEnd)
        val whEndRaw = if (workingHours.spansMidnight) dayEnd else date.atTime(workingHours.end).atZone(zone).toInstant()
        val whEnd = whEndRaw.coerceIn(dayStart, dayEnd)
        if (!whStart.isBefore(whEnd)) return emptyList()
        val window = InstantRange(whStart, whEnd)
        val busy = PlanBuilder.busyIntervals(date, zone, events, blocks)
        return PlanBuilder.freeIntervals(window, busy)
            // Keep only the part after now, and only gaps at least Min_Focus_Length long.
            .mapNotNull { gap ->
                val start = maxOf(gap.start, now)
                if (start.isBefore(gap.end)) InstantRange(start, gap.end) else null
            }
            .filter { it.duration >= minFocus }
    }

    /** Greedy first-fit of ranked open Tasks with estimates into [gap]; null when none fits. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun proposal(gap: InstantRange, rankedTasks: List<Task>, alreadyProposed: Set<TaskId>): FocusBlockProposal? {
        var remaining = gap.duration
        val chosen = ArrayList<Task>()
        for (task in rankedTasks) {
            if (task.id in alreadyProposed || chosen.any { it.id == task.id }) continue
            val minutes = task.estimateMinutes ?: continue
            val length = Duration.ofMinutes(minutes.toLong())
            if (length <= remaining) {
                chosen += task
                remaining = remaining.minus(length)
            }
        }
        if (chosen.isEmpty()) return null
        return FocusBlockProposal(gap, chosen.map { it.id }, chosen)
    }

    /** Places the proposed Tasks back to back from the later of the gap start and now (Requirement 25.4). */
    fun place(p: FocusBlockProposal, now: Instant): List<TimeBlock> {
        var cursor = maxOf(p.gap.start, now)
        val out = ArrayList<TimeBlock>()
        for (task in p.tasks) {
            val minutes = task.estimateMinutes ?: continue
            val end = cursor.plus(Duration.ofMinutes(minutes.toLong()))
            out += TimeBlock(id = null, taskId = task.id, range = InstantRange(cursor, end), kind = TimeBlockKind.FOCUS)
            cursor = end
        }
        return out
    }
}
