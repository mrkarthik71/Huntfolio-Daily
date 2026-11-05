package app.assistant.data.calendar

import app.assistant.data.db.entity.CalendarEventEntity
import app.assistant.data.db.entity.CalendarSourceEntity
import app.assistant.domain.engine.calendar.CalendarCachePolicy
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.CalendarReadError
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.DayEvents
import app.assistant.domain.model.RefreshStats
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.CalendarSource
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * The cached calendar (Requirements 22.2, 22.4, 22.8). `refresh` reads the provider for the policy
 * window, keeps only the events `CalendarCachePolicy.keep` accepts, and replaces the cache in one
 * transaction. A failed read leaves the cache exactly as it was and returns the error.
 */
class CalendarRepositoryImpl @Inject constructor(
    private val source: CalendarSource,
    private val holder: app.assistant.data.db.DbProvider,
    private val time: TimeSource,
    private val audit: AuditLog,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : CalendarRepository {

    private val dao get() = holder.database().calendarDao()

    private val lastRefresh = MutableStateFlow<Instant?>(null)

    override fun requestRefresh() = CalendarRefreshWorker.enqueue(context)

    override val lastSuccessfulRefresh: Flow<Instant?> = lastRefresh

    override val selectedCalendarIds: Flow<Set<Long>> = flow { emitAll(dao.selectedIds().map { it.toSet() }) }

    override val availableCalendars: Flow<List<app.assistant.domain.model.CalendarInfo>> =
        flow { emitAll(dao.sources().map { rows -> rows.map { app.assistant.domain.model.CalendarInfo(it.calendarId, it.displayName, it.accountName) } }) }

    override suspend fun loadCalendars(): Outcome<Unit, CalendarReadError> = when (val list = source.calendars()) {
        is Outcome.Err -> Outcome.Err(list.error)
        is Outcome.Ok -> {
            val existing = dao.selectedIds().first().toSet()
            // First use selects every calendar (Requirement 22.1): a calendar unseen before is selected.
            dao.upsertSources(
                list.value.map {
                    CalendarSourceEntity(it.id, it.displayName, it.accountName, selected = existing.isEmpty() || it.id in existing)
                },
            )
            Outcome.Ok(Unit)
        }
    }

    override suspend fun setSelectedCalendars(ids: Set<Long>) {
        // Refresh the source list so settings shows current calendars, then record the selection.
        (source.calendars() as? Outcome.Ok)?.value?.let { infos ->
            dao.upsertSources(infos.map { CalendarSourceEntity(it.id, it.displayName, it.accountName, it.id in ids) })
        }
    }

    override suspend fun refresh(): Outcome<RefreshStats, CalendarReadError> {
        val today = time.today()
        val zone = time.zone()
        val window = CalendarCachePolicy.window(today, zone)
        val selected = dao.selectedIds().first().toSet()

        val now = time.now()
        return when (val read = source.instances(window, selected)) {
            is Outcome.Err -> {
                // A failed read leaves the cache unchanged (Requirement 22.8).
                audit.recordRead(DataSource.CALENDAR, now, itemCount = 0, success = false)
                Outcome.Err(read.error)
            }
            is Outcome.Ok -> {
                val kept = read.value.filter { CalendarCachePolicy.keep(it, window, zone) }
                dao.replaceEvents(kept.map { it.toEntity() })
                lastRefresh.value = now
                audit.recordRead(DataSource.CALENDAR, now, itemCount = kept.size, success = true)
                Outcome.Ok(
                    RefreshStats(
                        kept = kept.size,
                        timed = kept.count { it.timed != null },
                        allDay = kept.count { it.isAllDay },
                        at = now,
                    ),
                )
            }
        }
    }

    override fun eventsOn(date: LocalDate): Flow<DayEvents> {
        val zone = time.zone()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val iso = date.toString()
        return combine(dao.timedBetween(dayStart, dayEnd), dao.allDayOn(iso)) { timed, allDay ->
            DayEvents(
                date = date,
                timed = timed.map { it.toFact() },
                allDay = allDay.map { it.toFact() },
            )
        }
    }
}

private fun CalendarEventFact.toEntity() = CalendarEventEntity(
    calendarId = calendarId,
    eventId = eventId,
    title = title,
    startAt = timed?.start?.toEpochMilli(),
    endAt = timed?.end?.toEpochMilli(),
    allDayStart = allDayStart?.toString(),
    allDayEnd = allDayEnd?.toString(),
)

private fun CalendarEventEntity.toFact() = CalendarEventFact(
    calendarId = calendarId,
    eventId = eventId,
    title = title,
    timed = if (startAt != null && endAt != null) {
        app.assistant.domain.model.InstantRange(Instant.ofEpochMilli(startAt), Instant.ofEpochMilli(endAt))
    } else {
        null
    },
    allDayStart = allDayStart?.let(LocalDate::parse),
    allDayEnd = allDayEnd?.let(LocalDate::parse),
    cancelled = false,
    declined = false,
)
