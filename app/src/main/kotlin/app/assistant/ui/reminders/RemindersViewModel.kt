package app.assistant.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.SmartReminder
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.ReminderScheduler
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class RemindersUi(
    val loading: Boolean = true,
    val reminders: List<SmartReminder> = emptyList(),
    val quietStart: java.time.LocalTime? = null,
    val quietEnd: java.time.LocalTime? = null,
)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val planner: PlannerRepository,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsStore,
    private val time: TimeSource,
) : ViewModel() {

    val state: StateFlow<RemindersUi> = combine(planner.reminders(), settings.settings) { list, s ->
        RemindersUi(false, list, s.quietHours?.start, s.quietHours?.end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), RemindersUi())

    fun today(): LocalDate = time.today()

    fun zone(): ZoneId = time.zone()

    /** Saves the reminder, turns on the reminder notification type (the user asked for reminders) and re-arms triggers. */
    fun save(reminder: SmartReminder) {
        if (reminder.message.isBlank()) return
        viewModelScope.launch {
            planner.saveReminder(reminder)
            settings.update { it.copy(enabledTypes = it.enabledTypes + NotificationType.SMART_REMINDER) }
            scheduler.sync()
        }
    }

    fun setActive(id: ReminderId, active: Boolean) {
        viewModelScope.launch {
            planner.setReminderActive(id, active)
            if (!active) scheduler.disarm(id)
            scheduler.sync()
        }
    }

    fun delete(id: ReminderId) {
        viewModelScope.launch {
            scheduler.disarm(id)
            planner.deleteReminder(id)
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
