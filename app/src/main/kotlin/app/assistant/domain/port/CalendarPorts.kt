package app.assistant.domain.port

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.CalendarInfo
import app.assistant.domain.model.CalendarReadError
import app.assistant.domain.model.DayEvents
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.RefreshStats
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Reads the device calendar through `CalendarContract` (design: Calendar_Reader). Read-only: it
 * never writes to the provider (Requirement 2). The adapter expands recurring events via
 * `CalendarContract.Instances` and filters cancelled and declined occurrences.
 */
interface CalendarSource {
    /** The calendars the device exposes, for the settings selection list (Requirement 22.1). */
    suspend fun calendars(): Outcome<List<CalendarInfo>, CalendarReadError>

    /** Expanded occurrences overlapping [window] for the selected [calendarIds]. */
    suspend fun instances(window: InstantRange, calendarIds: Set<Long>): Outcome<List<CalendarEventFact>, CalendarReadError>

    /** Emits when the provider signals a change (a ContentObserver on `CalendarContract.CONTENT_URI`). */
    val changes: Flow<Unit>
}

/**
 * The cached calendar as the rest of the app reads it. A refresh replaces the cache in one
 * transaction and leaves it unchanged on failure (Requirements 22.4, 22.8).
 */
interface CalendarRepository {
    suspend fun refresh(): Outcome<RefreshStats, CalendarReadError>

    /** Enqueues a background refresh (used by settings after a selection change). */
    fun requestRefresh()

    fun eventsOn(date: LocalDate): Flow<DayEvents>

    val lastSuccessfulRefresh: Flow<Instant?>

    /** The calendars the User has selected to read; empty means "all" on first use (Requirement 22.1). */
    val selectedCalendarIds: Flow<Set<Long>>

    /** The calendars known to the app, for the settings selection list, each with its selected flag. */
    val availableCalendars: Flow<List<CalendarInfo>>

    /** Reads the device calendar list into the store so [availableCalendars] is current. */
    suspend fun loadCalendars(): Outcome<Unit, CalendarReadError>

    suspend fun setSelectedCalendars(ids: Set<Long>)
}
