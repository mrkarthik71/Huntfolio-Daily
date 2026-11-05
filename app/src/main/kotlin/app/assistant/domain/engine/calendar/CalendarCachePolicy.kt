package app.assistant.domain.engine.calendar

import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.InstantRange
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure rules for the calendar cache (Requirements 22.3, 22.6). The cache holds exactly the events
 * that overlap a fixed 8-day window and are neither cancelled nor declined; everything else is
 * dropped. No Android or clock access — the caller passes `today` and `zone` from the Calendar_Reader.
 */
object CalendarCachePolicy {

    /** The cache window: 00:00 today to 24:00 on today + 7 (an 8-day span), in [zone]. */
    fun window(today: LocalDate, zone: ZoneId): InstantRange {
        val start = today.atStartOfDay(zone).toInstant()
        // 24:00 on today+7 is the start of today+8.
        val end = today.plusDays(8).atStartOfDay(zone).toInstant()
        return InstantRange(start, end)
    }

    /**
     * True when [e] belongs in the cache: not cancelled, not declined, and overlapping [window].
     * A timed event overlaps by its [InstantRange]; an all-day event overlaps when its date span
     * (converted to instants in [zone], end exclusive) touches the window.
     */
    fun keep(e: CalendarEventFact, window: InstantRange, zone: ZoneId): Boolean {
        if (e.cancelled || e.declined) return false
        val range = when {
            e.timed != null -> e.timed
            e.allDayStart != null && e.allDayEnd != null -> InstantRange(
                e.allDayStart.atStartOfDay(zone).toInstant(),
                e.allDayEnd.atStartOfDay(zone).toInstant(),
            )
            else -> return false
        }
        return range.overlaps(window)
    }
}
