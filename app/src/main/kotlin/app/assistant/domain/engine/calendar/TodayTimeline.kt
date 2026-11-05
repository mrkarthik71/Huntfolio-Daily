package app.assistant.domain.engine.calendar

import app.assistant.domain.model.TimedEvent
import java.time.Instant
import java.time.ZoneId

/** One timeline row for the Today_Screen: a timed event with its start on the current date. */
data class TimelineEntry(val event: TimedEvent, val startsEarlier: Boolean)

/**
 * Pure ordering of a date's timed events for the Today_Screen (Requirements 15.1, 23.1, 23.5). An
 * event that started before the date is ordered as if it began at 00:00 (`startsEarlier`), so the
 * screen can show the date alongside its time. Ties break by end, then title. No clock access —
 * the caller passes `date`'s day window as instants.
 */
object TodayTimeline {

    /** Timed events overlapping [dayStart, dayEnd), ordered by effective start, then end, then title. */
    fun order(events: List<TimedEvent>, dayStart: Instant, dayEnd: Instant): List<TimelineEntry> = events
        .filterNot { it.range.isEmpty }
        .filter { it.range.start < dayEnd && it.range.end > dayStart }
        .map { TimelineEntry(it, startsEarlier = it.range.start.isBefore(dayStart)) }
        .sortedWith(
            compareBy(
                { if (it.startsEarlier) dayStart else it.event.range.start },
                { it.event.range.end },
                { it.event.title },
            ),
        )

    /** The next timed event starting at or after [now] on the date, for the Widget (Requirement 21.5). */
    fun nextEvent(events: List<TimedEvent>, now: Instant, dayEnd: Instant): TimedEvent? = events
        .filterNot { it.range.isEmpty }
        .filter { !it.range.start.isBefore(now) && it.range.start.isBefore(dayEnd) }
        .minWithOrNull(compareBy({ it.range.start }, { it.range.end }, { it.title }))

    fun dayWindow(date: java.time.LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()
}
