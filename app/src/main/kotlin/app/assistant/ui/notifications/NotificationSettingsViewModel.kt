package app.assistant.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.LogEntry
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

data class NotificationSettingsUi(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val hasExactAlarmAccess: Boolean = true,
    val inbox: List<LogEntry> = emptyList(),
)

/** The three Notification_Types Phase 1 can schedule; the rest arrive with their feature phase. */
val PHASE1_TYPES = listOf(
    NotificationType.MORNING_BRIEF,
    NotificationType.EVENING_REVIEW,
    NotificationType.GOAL_NUDGE,
)

/** Calendar-backed Notification_Types, shown only when Phase 2 Calendar is on. */
val PHASE2_TYPES = listOf(NotificationType.EVENT_REMINDER)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val scheduler: NotificationScheduling,
    private val permissions: app.assistant.ui.permission.PermissionManager,
    log: NotificationLog,
) : ViewModel() {

    val state: StateFlow<NotificationSettingsUi> = combine(settingsStore.settings, log.inbox()) { settings, inbox ->
        NotificationSettingsUi(
            loading = false,
            settings = settings,
            hasExactAlarmAccess = scheduler.hasExactAlarmAccess(),
            inbox = inbox,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUi())

    /** Every change refreshes the scheduler so new times and switched-off types take effect at once. */
    private fun apply(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsStore.update(change)
            scheduler.refresh()
        }
    }

    /**
     * Turning a type on asks for notification permission first (Requirement 5.1, 5.2). The type is
     * enabled regardless of the answer; without permission it waits in the inbox, which the screen
     * explains. Turning a type off never asks.
     */
    fun toggleType(type: NotificationType, on: Boolean) {
        viewModelScope.launch {
            if (on) permissions.request(app.assistant.ui.permission.Feature.NOTIFICATIONS)
            settingsStore.update {
                it.copy(enabledTypes = if (on) it.enabledTypes + type else it.enabledTypes - type)
            }
            scheduler.refresh()
        }
    }

    fun setMorningTime(time: LocalTime) = apply { it.copy(morningBrief = time) }

    fun setEveningTime(time: LocalTime) = apply { it.copy(eveningReview = time) }

    fun setDailyCap(cap: Int) = apply { it.copy(dailyCap = cap) }

    fun openExactAlarmSettings() = permissions.openExactAlarmSettings()
}
