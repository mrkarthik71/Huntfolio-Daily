package app.assistant.ui.permission

import kotlinx.coroutines.flow.Flow

/** App features that need an Android permission or special access (Requirement 5). */
enum class Feature { NOTIFICATIONS, CALENDAR, SMS, NOTIFICATION_ACCESS, EXACT_ALARM, MICROPHONE }

enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED, NOT_REQUESTED }

/**
 * How a feature is granted: a runtime permission group shown in one system dialog
 * (Requirement 5.3), or a system settings page the User toggles by hand.
 */
sealed interface Grant {
    data class RuntimeGroup(val permissions: List<String>) : Grant

    data object NotificationAccessPage : Grant

    data object ExactAlarmPage : Grant

    /** Nothing to ask on this Android version (for example notifications before Android 13). */
    data object Implicit : Grant
}

interface PermissionManager {
    fun status(feature: Feature): PermissionStatus

    /**
     * Shows the system dialog or opens the settings page for [feature] and returns the new status.
     * The caller shows the Permission_Rationale first (Requirement 5.2); this method never does.
     */
    suspend fun request(feature: Feature): PermissionStatus

    fun openAppSettings()

    fun openNotificationAccessSettings()

    fun openExactAlarmSettings()

    /** Features whose access was granted before and is now gone (Requirement 5.7). */
    val revocations: Flow<Feature>

    /** Re-checks every feature; called on start, on foreground and before background reads. */
    fun checkRevocations()
}

/** Pure status rule, kept separate so it can be tested without Android. */
object PermissionLogic {
    fun status(granted: Boolean, requestedBefore: Boolean, systemWillShowDialog: Boolean): PermissionStatus = when {
        granted -> PermissionStatus.GRANTED
        !requestedBefore -> PermissionStatus.NOT_REQUESTED
        // Android stops showing the dialog after repeated denials (Requirement 5.6).
        !systemWillShowDialog -> PermissionStatus.PERMANENTLY_DENIED
        else -> PermissionStatus.DENIED
    }

    /** Features granted at the previous check and not granted now. */
    fun revoked(previouslyGranted: Set<Feature>, grantedNow: Set<Feature>): Set<Feature> = previouslyGranted - grantedNow
}
