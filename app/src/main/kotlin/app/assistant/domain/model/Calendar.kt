package app.assistant.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * One calendar the device exposes (Requirement 22.1). Shown in settings with its display and
 * account names so the User can choose which calendars are read.
 */
data class CalendarInfo(val id: Long, val displayName: String, val accountName: String)

/**
 * A single calendar occurrence as the Calendar_Reader sees it (design: Calendar_Reader). Timed
 * events carry an [InstantRange]; all-day events carry [allDayStart]/[allDayEnd] as calendar dates,
 * because the provider stores all-day events in UTC and a date has no zone (Requirement 22.5). A
 * fact is never both timed and all-day.
 */
data class CalendarEventFact(
    val calendarId: Long,
    val eventId: Long,
    val title: String,
    val timed: InstantRange?,
    val allDayStart: LocalDate?,
    val allDayEnd: LocalDate?,
    val cancelled: Boolean,
    val declined: Boolean,
) {
    val isAllDay: Boolean get() = timed == null && allDayStart != null

    init {
        require((timed != null) != (allDayStart != null)) { "A CalendarEventFact is either timed or all-day, not both or neither" }
        if (allDayStart != null) require(allDayEnd != null) { "An all-day event needs an end date" }
    }
}

/** Why a calendar read failed (Requirement 22.8); a failed read leaves the cache unchanged. */
enum class CalendarReadError { PERMISSION_DENIED, PROVIDER_UNAVAILABLE, UNKNOWN }

/** What one refresh changed, for the Audit_Log read entry and diagnostics (counts only). */
data class RefreshStats(val kept: Int, val timed: Int, val allDay: Int, val at: Instant)

/** Timed and all-day events for one date, as the Today timeline and plan consume them. */
data class DayEvents(val date: LocalDate, val timed: List<CalendarEventFact>, val allDay: List<CalendarEventFact>)
