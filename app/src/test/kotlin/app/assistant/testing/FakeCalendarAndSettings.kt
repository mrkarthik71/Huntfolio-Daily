package app.assistant.testing

import app.assistant.domain.engine.settings.SettingsField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.CalendarInfo
import app.assistant.domain.model.CalendarReadError
import app.assistant.domain.model.DayEvents
import app.assistant.domain.model.RefreshStats
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate

/** In-memory calendar cache for ViewModel and UI tests; set [day] and [refreshedAt] to drive the Today states. */
class FakeCalendarRepository(day: (LocalDate) -> DayEvents = { DayEvents(it, emptyList(), emptyList()) }, refreshedAt: Instant? = null) :
    CalendarRepository {
    private val dayOf = day
    private val refreshed = MutableStateFlow(refreshedAt)

    override suspend fun refresh(): Outcome<RefreshStats, CalendarReadError> = Outcome.Ok(RefreshStats(0, 0, 0, Instant.EPOCH))

    override fun requestRefresh() = Unit

    override fun eventsOn(date: LocalDate): Flow<DayEvents> = MutableStateFlow(dayOf(date))

    override val lastSuccessfulRefresh: Flow<Instant?> = refreshed

    override val selectedCalendarIds: Flow<Set<Long>> = MutableStateFlow(emptySet())

    override val availableCalendars: Flow<List<CalendarInfo>> = MutableStateFlow(emptyList())

    override suspend fun loadCalendars(): Outcome<Unit, CalendarReadError> = Outcome.Ok(Unit)

    override suspend fun setSelectedCalendars(ids: Set<Long>) = Unit
}

/** Settings held in memory; [update] applies the change without validation. */
class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun current(): AppSettings = state.value

    override suspend fun update(change: (AppSettings) -> AppSettings): Outcome<AppSettings, FieldErrors<SettingsField>> {
        state.value = change(state.value)
        return Outcome.Ok(state.value)
    }
}
