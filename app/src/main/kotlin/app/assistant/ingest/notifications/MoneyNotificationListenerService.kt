package app.assistant.ingest.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.assistant.domain.model.DataSource
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.ReaderStateStore
import app.assistant.ingest.pipeline.MessageIngestor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

/**
 * The Notification_Reader (Requirement 27). While notification reading is on and access is granted, a notification posted
 * by a Money_App_List app has its title and text (only) passed to the pipeline within a moment of posting (27.1). It never
 * cancels, replies to, or triggers the actions of another app's notification (Requirement 2.3) — it only reads. The
 * component is declared disabled behind @bool/phase_money, so it ships off until the money phase is on.
 *
 * Android binds this service when the User grants notification access; `onListenerConnected`/`onListenerDisconnected`
 * track the live connection that `ReaderStatus` reads. The title/text extraction and the empty-notification drop are in
 * the pure `NotificationMapper` (Requirement 27.6); the listed-app check is in `MoneyAppList`. Everything is dropped when
 * reading is switched off, so removing an app stops its notifications being read while its Transactions stay (27.7).
 */
@AndroidEntryPoint
class MoneyNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var ingestor: MessageIngestor

    @Inject lateinit var moneyApps: MoneyAppList

    @Inject lateinit var readerState: ReaderStateStore

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        scope.launch { handle(notification) }
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        if (!readerState.current().notificationsEnabled) return
        val packageName = sbn.packageName ?: return
        if (!moneyApps.contains(packageName)) return
        val extras = sbn.notification?.extras
        val posted = PostedNotification(
            packageName = packageName,
            key = sbn.key ?: "$packageName:${sbn.id}:${sbn.postTime}",
            title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE),
            text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT),
            postedAt = Instant.ofEpochMilli(sbn.postTime),
        )
        val message = NotificationMapper.toMessage(posted) ?: return
        withContext(dispatchers.io) {
            ingestor.ingestBatch(DataSource.APP_NOTIFICATIONS, listOf(message), readSucceeded = true)
        }
    }

    // We never remove a notification; onNotificationRemoved is intentionally not overridden.

    companion object {
        /** Whether the system currently has this listener connected, for `ReaderStatus` (Requirement 27.4). */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
