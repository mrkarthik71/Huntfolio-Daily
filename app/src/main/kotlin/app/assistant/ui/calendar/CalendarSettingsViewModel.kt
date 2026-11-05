package app.assistant.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CalendarInfo
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.SettingsStore
import app.assistant.ui.permission.Feature
import app.assistant.ui.permission.PermissionManager
import app.assistant.ui.permission.PermissionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalendarSettingsUi(
    val loading: Boolean = true,
    val granted: Boolean = false,
    val calendars: List<CalendarRow> = emptyList(),
    val minFocusMinutes: Int = 30,
    val eventLeadMinutes: Int = 15,
)

data class CalendarRow(val info: CalendarInfo, val selected: Boolean)

/**
 * The calendar-selection list (Requirement 22.1), shown behind the calendar Permission_Rationale,
 * plus the two calendar settings: Min_Focus_Length and Event_Lead_Time. Granting permission loads
 * the device calendars; toggling a calendar writes the selection and asks the repository to
 * refresh so the cache follows the new set. It talks only to domain ports.
 */
@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val permissions: PermissionManager,
    private val settings: SettingsStore,
    private val scheduler: NotificationScheduling,
) : ViewModel() {

    val state: StateFlow<CalendarSettingsUi> =
        combine(repository.availableCalendars, repository.selectedCalendarIds, settings.settings) { calendars, selected, s ->
            CalendarSettingsUi(
                loading = false,
                granted = permissions.status(Feature.CALENDAR) == PermissionStatus.GRANTED,
                calendars = calendars.map { CalendarRow(it, it.id in selected || selected.isEmpty()) },
                minFocusMinutes = s.minFocusLength.toMinutes().toInt(),
                eventLeadMinutes = s.eventLeadTime.toMinutes().toInt(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarSettingsUi())

    /** Asks for the calendar permission, then loads the device calendars and refreshes the cache. */
    fun grantAndLoad() {
        viewModelScope.launch {
            if (permissions.request(Feature.CALENDAR) == PermissionStatus.GRANTED) {
                if (repository.loadCalendars() is Outcome.Ok) repository.requestRefresh()
            }
        }
    }

    /** Loads the list on open when permission is already granted. */
    fun loadIfGranted() {
        if (permissions.status(Feature.CALENDAR) == PermissionStatus.GRANTED) {
            viewModelScope.launch {
                if (repository.loadCalendars() is Outcome.Ok) repository.requestRefresh()
            }
        }
    }

    fun toggle(id: Long, selected: Boolean) {
        viewModelScope.launch {
            val current = state.value.calendars.filter { it.selected }.map { it.info.id }.toMutableSet()
            if (selected) current += id else current -= id
            repository.setSelectedCalendars(current)
            repository.requestRefresh()
        }
    }

    /** Changes Min_Focus_Length; an out-of-range value is rejected and the old one kept (Requirement 25.1). */
    fun adjustMinFocus(deltaMinutes: Int) {
        viewModelScope.launch {
            settings.update { it.copy(minFocusLength = it.minFocusLength.plusMinutes(deltaMinutes.toLong())) }
        }
    }

    /** Changes Event_Lead_Time and re-plans pending reminders to the new lead (Requirement 24.2). */
    fun adjustEventLead(deltaMinutes: Int) {
        viewModelScope.launch {
            settings.update { it.copy(eventLeadTime = it.eventLeadTime.plusMinutes(deltaMinutes.toLong())) }
            scheduler.refreshEventReminders()
        }
    }
}
