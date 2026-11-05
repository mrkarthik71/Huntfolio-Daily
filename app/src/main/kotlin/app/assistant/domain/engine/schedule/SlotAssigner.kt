package app.assistant.domain.engine.schedule

import app.assistant.domain.model.EnergyTag
import java.time.LocalTime

/** A stretch of the day in minutes from local midnight, [start] inclusive, [end] exclusive. */
data class Span(val start: Int, val end: Int) {
    val minutes: Int get() = end - start
    init {
        require(start in 0..MINUTES_PER_DAY && end in 0..MINUTES_PER_DAY && start <= end) { "bad span $start..$end" }
    }
    fun overlaps(o: Span): Boolean = start < o.end && o.start < end

    companion object {
        fun of(from: LocalTime, to: LocalTime) = Span(from.toSecondOfDay() / SECONDS, to.toSecondOfDay() / SECONDS)
    }
}

/** One piece of work to place today. Lower [priority] number = more important (1 to 5). */
data class Work(
    val id: Long,
    val minutes: Int,
    val priority: Int,
    val energy: EnergyTag,
    /** Days until it is due; null when it has no due date. 0 = due today. */
    val dueInDays: Int?,
)

/** A placed piece of work. Long work can be split into several [Placement]s of the same [workId]. */
data class Placement(val workId: Long, val span: Span, val energy: EnergyTag)

data class Assignment(val placements: List<Placement>, val unplacedMinutes: Map<Long, Int>) {
    val bookedMinutes: Int get() = placements.sumOf { it.span.minutes }
}

/** When each kind of energy is usually best (the User's profile; these are the defaults). */
data class EnergyWindows(val windows: Map<EnergyTag, List<Span>>) {
    fun fits(tag: EnergyTag, span: Span): Boolean = windows[tag].isNullOrEmpty() || windows.getValue(tag).any { it.overlaps(span) }

    companion object {
        val Default = EnergyWindows(
            mapOf(
                EnergyTag.DEEP_WORK to listOf(Span(9 * 60, 12 * 60 + 30), Span(15 * 60, 17 * 60 + 30)),
                EnergyTag.LIGHT_ADMIN to listOf(Span(12 * 60 + 30, 15 * 60), Span(17 * 60, 19 * 60)),
                EnergyTag.PHYSICAL_ROUTINE to listOf(Span(6 * 60, 9 * 60), Span(18 * 60, 21 * 60)),
                EnergyTag.REST to emptyList(),
            ),
        )
    }
}

/**
 * Places work into free time (design: SlotAssigner). Deterministic and pure:
 * 1. order work by urgency score = (6 - priority) * 10 + urgency, urgency = 100 / (1 + dueInDays)
 *    (no due date = 0), ties by id;
 * 2. each item goes into the earliest free span that matches its energy window, else any free span;
 * 3. work longer than [maxChunk] is split into chunks of at least [minChunk] minutes; a remainder
 *    that cannot be placed is reported in [Assignment.unplacedMinutes], never dropped silently.
 */
object SlotAssigner {
    const val MIN_CHUNK = 25
    const val MAX_CHUNK = 120
    private const val BUFFER = 5

    fun score(w: Work): Int {
        val urgency = w.dueInDays?.let { URGENCY_SCALE / (1 + it.coerceAtLeast(0)) } ?: 0
        return (PRIORITY_BASE - w.priority.coerceIn(1, PRIORITY_MAX)) * PRIORITY_WEIGHT + urgency
    }

    fun assign(work: List<Work>, free: List<Span>, windows: EnergyWindows = EnergyWindows.Default, minChunk: Int = MIN_CHUNK, maxChunk: Int = MAX_CHUNK): Assignment {
        val open = free.filter { it.minutes >= minChunk }.sortedBy { it.start }.toMutableList()
        val placements = mutableListOf<Placement>()
        val unplaced = linkedMapOf<Long, Int>()
        for (w in work.sortedWith(compareByDescending<Work> { score(it) }.thenBy { it.id })) {
            var left = w.minutes.coerceAtLeast(minChunk)
            while (left > 0) {
                val want = minOf(left, maxChunk)
                val idx = pick(open, want, minChunk, w.energy, windows) ?: break
                val slot = open[idx]
                val take = minOf(want, slot.minutes)
                val placed = Span(slot.start, slot.start + take)
                placements += Placement(w.id, placed, w.energy)
                left -= take
                open.removeAt(idx)
                val restStart = (placed.end + BUFFER).coerceAtMost(slot.end)
                if (slot.end - restStart >= minChunk) open.add(idx, Span(restStart, slot.end))
            }
            if (left > 0) unplaced[w.id] = left
        }
        return Assignment(placements.sortedBy { it.span.start }, unplaced)
    }

    private fun pick(open: List<Span>, want: Int, minChunk: Int, energy: EnergyTag, windows: EnergyWindows): Int? {
        val need = minOf(want, maxOf(minChunk, want))
        val matching = open.indexOfFirst { it.minutes >= need && windows.fits(energy, Span(it.start, it.start + need)) }
        if (matching >= 0) return matching
        val anyFull = open.indexOfFirst { it.minutes >= need }
        if (anyFull >= 0) return anyFull
        return open.indexOfFirst { it.minutes >= minChunk }.takeIf { it >= 0 }
    }

    private const val URGENCY_SCALE = 100
    private const val PRIORITY_BASE = 6
    private const val PRIORITY_MAX = 5
    private const val PRIORITY_WEIGHT = 10
}

/** Free time inside [day] once every busy span is removed (busy spans may overlap). */
object FreeTime {
    fun of(day: Span, busy: List<Span>): List<Span> {
        val result = mutableListOf<Span>()
        var cursor = day.start
        for (b in busy.filter { it.overlaps(day) }.sortedBy { it.start }) {
            if (b.start > cursor) result += Span(cursor, minOf(b.start, day.end))
            cursor = maxOf(cursor, b.end)
            if (cursor >= day.end) break
        }
        if (cursor < day.end) result += Span(cursor, day.end)
        return result.filter { it.minutes > 0 }
    }
}

/** How full a day is: booked minutes over the minutes the User is available, as a whole percent. */
object DayLoad {
    fun percent(bookedMinutes: Int, availableMinutes: Int): Int =
        if (availableMinutes <= 0) (if (bookedMinutes > 0) OVER_FULL else 0) else (bookedMinutes * PERCENT + availableMinutes / 2) / availableMinutes

    const val OVERLOADED = 100
    private const val OVER_FULL = 999
    private const val PERCENT = 100
}

/** What changed between two plans, for the "Review moves" sheet. */
data class ScheduleDiff(val moved: List<Pair<Placement, Placement>>, val unchanged: List<Placement>, val unplaced: Map<Long, Int>) {
    val isEmpty: Boolean get() = moved.isEmpty() && unplaced.isEmpty()
}

/**
 * Re-plans the rest of the day after an overrun or a spill-over (design: Rescheduler): everything
 * that starts before [from] stays; work from [from] on is re-assigned into free time that now
 * starts at [from], with [busy] (meetings, locked blocks) left in place. Returns a minimal diff.
 */
object Rescheduler {
    fun reshuffle(current: List<Placement>, work: List<Work>, day: Span, busy: List<Span>, from: Int, windows: EnergyWindows = EnergyWindows.Default): ScheduleDiff {
        val kept = current.filter { it.span.start < from }
        val movable = current.filter { it.span.start >= from }.map { it.workId }.toSet()
        val toPlace = work.filter { it.id in movable }.map { w ->
            w.copy(minutes = current.filter { it.workId == w.id && it.span.start >= from }.sumOf { it.span.minutes })
        }
        val free = FreeTime.of(Span(from.coerceIn(day.start, day.end), day.end), busy + kept.map { it.span })
        val next = SlotAssigner.assign(toPlace, free, windows)
        val before = current.filter { it.workId in movable }
        val moved = mutableListOf<Pair<Placement, Placement>>()
        val unchanged = kept.toMutableList()
        next.placements.forEach { p ->
            val old = before.firstOrNull { it.workId == p.workId && it.span == p.span }
            if (old != null) unchanged += p else before.firstOrNull { it.workId == p.workId }?.let { moved += it to p }
        }
        return ScheduleDiff(moved, unchanged.sortedBy { it.span.start }, next.unplacedMinutes)
    }
}

const val MINUTES_PER_DAY = 1_440
private const val SECONDS = 60
