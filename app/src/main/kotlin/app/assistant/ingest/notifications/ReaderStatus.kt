package app.assistant.ingest.notifications

/** Whether a reader is working, for the Settings_Screen and the Health_Check (Requirements 27.4, 27.5). */
enum class ReaderState {
    /** The User has not switched it on. */
    OFF,

    /** Switched on, but the Android permission has not been given, so nothing is read. */
    NEEDS_PERMISSION,

    /** Reading. */
    ACTIVE,

    /** Switched on, but Android has stopped delivering notifications, so the User must turn access back on. */
    DISCONNECTED,
}

/** The state of the notification reader, worked out from three facts the platform reports. Pure. */
object ReaderStatus {
    /**
     * [accessGranted] is Android's notification access for the app, and [listenerConnected] whether the system is
     * currently delivering to it. Access lost or listener dropped while switched on is a disconnection the User is told about
     * on the next app open (Requirement 27.4).
     */
    fun notifications(enabled: Boolean, accessGranted: Boolean, listenerConnected: Boolean): ReaderState = when {
        !enabled -> ReaderState.OFF
        !accessGranted || !listenerConnected -> ReaderState.DISCONNECTED
        else -> ReaderState.ACTIVE
    }

    /**
     * Whether notification reading is on after the User comes back from Android's access settings: only when access was
     * actually granted, so returning without granting keeps it off and shown as disconnected (Requirement 27.5).
     */
    fun notificationsEnabledAfterSettings(accessGranted: Boolean): Boolean = accessGranted
}
