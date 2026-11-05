package app.assistant.domain.model

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * A half-open time range `[start, end)`. Two ranges that only touch at an end point do not
 * overlap (Glossary: Overlap; Requirement 23.3).
 */
data class InstantRange(val start: Instant, val end: Instant) {
    init {
        require(!end.isBefore(start)) { "InstantRange end is before start" }
    }

    val duration: Duration get() = Duration.between(start, end)

    val isEmpty: Boolean get() = start == end

    /** True when the ranges share a period longer than zero; an empty range overlaps nothing. */
    fun overlaps(other: InstantRange): Boolean = !isEmpty && !other.isEmpty && start < other.end && other.start < end

    operator fun contains(instant: Instant): Boolean = !instant.isBefore(start) && instant.isBefore(end)

    fun contains(other: InstantRange): Boolean = !other.start.isBefore(start) && !other.end.isAfter(end)

    /** The shared part, or null when the ranges do not overlap. */
    fun intersect(other: InstantRange): InstantRange? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (s < e) InstantRange(s, e) else null
    }
}

/**
 * A daily clock-time window such as Working_Hours or Quiet_Hours. When [end] is before
 * [start] the window spans midnight (for example 22:30 to 07:00). Equal start and end is
 * an empty window: settings validation rejects it for windows that must be non-empty.
 */
data class LocalTimeRange(val start: LocalTime, val end: LocalTime) {
    val spansMidnight: Boolean get() = end < start

    val isEmpty: Boolean get() = start == end

    /** Half-open: [start] is inside, [end] is outside. */
    operator fun contains(time: LocalTime): Boolean = when {
        isEmpty -> false
        spansMidnight -> time >= start || time < end
        else -> time >= start && time < end
    }

    val duration: Duration
        get() = when {
            isEmpty -> Duration.ZERO
            spansMidnight -> Duration.between(start, LocalTime.MAX).plusNanos(1).plus(Duration.between(LocalTime.MIDNIGHT, end))
            else -> Duration.between(start, end)
        }
}
