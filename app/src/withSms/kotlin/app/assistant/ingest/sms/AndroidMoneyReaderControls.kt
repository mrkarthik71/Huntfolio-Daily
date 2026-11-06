package app.assistant.ingest.sms

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.MoneyReaderControls
import app.assistant.domain.port.MoneyReaderState
import app.assistant.domain.port.ReaderStateStore
import app.assistant.domain.port.TimeSource
import app.assistant.ingest.notifications.MoneyNotificationListenerService
import app.assistant.ingest.notifications.ReaderState
import app.assistant.ingest.notifications.ReaderStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real money reader controls for the withSms build (Requirements 26, 27). It owns no reading itself: the switches
 * update the reader-state DataStore and enqueue the SMS workers, and the SMS receiver + notification listener do the
 * reading. The state flows combine the stored switches with the live platform facts (SMS permission, notification access
 * and the listener connection) so the money settings show NEEDS_PERMISSION / DISCONNECTED correctly (26.2, 27.4, 27.5).
 *
 * The caller (money settings) shows the Permission_Rationale before enabling; nothing here reads message content.
 */
@Singleton
class AndroidMoneyReaderControls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val readerState: ReaderStateStore,
    private val importReporter: SmsImportReporter,
    private val time: TimeSource,
    @param:ApplicationScope private val scope: CoroutineScope,
) : MoneyReaderControls {

    override val smsState: StateFlow<MoneyReaderState> = readerState.flags
        .map { flags -> smsStateOf(flags.smsEnabled) }
        .stateIn(scope, SharingStarted.Eagerly, MoneyReaderState.OFF)

    override val historyImport: StateFlow<HistoryImportState> = importReporter.state

    override val notificationState: StateFlow<MoneyReaderState> = readerState.flags
        .combine(importReporter.state) { flags, _ -> flags.notificationsEnabled }
        .map { enabled -> notificationStateOf(enabled) }
        .stateIn(scope, SharingStarted.Eagerly, MoneyReaderState.OFF)

    override suspend fun enableSms() {
        readerState.enableSms(time.now())
        // Catch up on anything that arrived before reading was switched on only from the enabled-at instant (26.6).
        SmsCatchupWorker.enqueue(context)
    }

    override suspend fun disableSms() {
        readerState.disableSms()
    }

    override suspend fun runHistoryImport() {
        SmsHistoryImportWorker.enqueue(context)
    }

    override suspend fun declineHistoryImport() {
        readerState.markHistoryImportOffered()
    }

    override suspend fun enableNotifications() {
        readerState.setNotificationsEnabled(true)
    }

    override suspend fun disableNotifications() {
        readerState.setNotificationsEnabled(false)
    }

    override fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some phones have no such screen; a missing page must not crash the app.
        runCatching { context.startActivity(intent) }
    }

    override suspend fun refreshNotifications() {
        // Returning from Android settings without granting access keeps reading off (27.5).
        if (!notificationAccessGranted()) readerState.setNotificationsEnabled(false)
    }

    private fun smsStateOf(enabled: Boolean): MoneyReaderState = when {
        !enabled -> MoneyReaderState.OFF
        !smsPermissionGranted() -> MoneyReaderState.NEEDS_PERMISSION
        else -> MoneyReaderState.ACTIVE
    }

    private fun notificationStateOf(enabled: Boolean): MoneyReaderState =
        ReaderStatus.notifications(enabled, notificationAccessGranted(), MoneyNotificationListenerService.connected).toMoneyState()

    private fun smsPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun notificationAccessGranted(): Boolean {
        val listener = ComponentName(context, MoneyNotificationListenerService::class.java)
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) &&
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().contains(listener.flattenToString())
    }
}

private fun ReaderState.toMoneyState(): MoneyReaderState = when (this) {
    ReaderState.OFF -> MoneyReaderState.OFF
    ReaderState.NEEDS_PERMISSION -> MoneyReaderState.NEEDS_PERMISSION
    ReaderState.ACTIVE -> MoneyReaderState.ACTIVE
    ReaderState.DISCONNECTED -> MoneyReaderState.DISCONNECTED
}
