package app.assistant.domain.engine.calendar

import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import app.assistant.domain.model.TimedEvent

/**
 * Finds Time_Blocks that overlap a timed Calendar_Event and suggests where to move one
 * (Requirement 23.6). Pure. A conflicting block is never moved automatically — the Today_Screen
 * offers the move and the Plan_Builder keeps the block until the User accepts.
 */
object ConflictDetector {

    /** Ids of Time_Blocks that overlap any event. Blocks without an id (unsaved) are ignored. */
    fun conflicts(blocks: List<TimeBlock>, events: List<TimedEvent>): Set<TimeBlockId> {
        val eventRanges = events.map { it.range }.filterNot { it.isEmpty }
        return blocks
            .filter { b -> b.id != null && eventRanges.any { it.overlaps(b.range) } }
            .mapNotNull { it.id }
            .toSet()
    }

    /**
     * The earliest later Free_Gap on the date long enough to hold [block], or null when none fits
     * (the caller then offers removal). [gaps] are the current Free_Gaps in start order.
     */
    @Suppress("UnusedParameter") // `date` is part of the design's contract; kept for call-site clarity.
    fun suggestMove(block: TimeBlock, date: java.time.LocalDate, gaps: List<InstantRange>): InstantRange? {
        val need = block.range.duration
        return gaps
            .filter { !it.start.isBefore(block.range.start) && it.duration >= need }
            .minByOrNull { it.start }
            ?.let { InstantRange(it.start, it.start.plus(need)) }
    }
}
