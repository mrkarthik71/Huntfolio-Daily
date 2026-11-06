package app.assistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.RescheduleReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs [block] off the main thread and tells Android when the receiver is finished. */
private fun BroadcastReceiver.async(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            // A failing alarm must never crash the app; the next trigger tries again.
            runCatching { block() }
        } finally {
            pending.finish()
        }
    }
}

/** The single system alarm fired: show everything that is due (Requirement 19.2). */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: NotificationScheduling

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        async(scope) { scheduler.onAlarmFired() }
    }
}

/** Boot, time, zone, update and exact-alarm changes reschedule everything (Requirements 19.1, 19.4, 65.5). */
@AndroidEntryPoint
class SystemEventReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: NotificationScheduling

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val reason = rescheduleReasonFor(intent.action) ?: return
        async(scope) { scheduler.rescheduleAll(reason) }
    }
}

private const val ACTION_EXACT_ALARM_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

/** Maps a system broadcast action to the reason for rescheduling; null for any other action. */
internal fun rescheduleReasonFor(action: String?): RescheduleReason? = when (action) {
    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> RescheduleReason.BOOT
    Intent.ACTION_TIME_CHANGED -> RescheduleReason.TIME_CHANGED
    Intent.ACTION_TIMEZONE_CHANGED -> RescheduleReason.ZONE_CHANGED
    Intent.ACTION_MY_PACKAGE_REPLACED -> RescheduleReason.PACKAGE_REPLACED
    ACTION_EXACT_ALARM_CHANGED -> RescheduleReason.EXACT_ALARM_CHANGED
    else -> null
}

/** The Snooze (Requirement 18.5) and Done buttons on a notification. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: NotificationScheduling

    @Inject lateinit var life: LifeNotifications

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotifyTargets.ACTION_DONE) {
            val ref = intent.getStringExtra(NotifyTargets.EXTRA_REF) ?: return
            val id = intent.getLongExtra(NotifyTargets.EXTRA_LOG_ID, -1L)
            async(scope) {
                life.done(ref)
                scheduler.refresh()
            }
            if (id >= 0) androidx.core.app.NotificationManagerCompat.from(context).cancel(id.toInt())
            return
        }
        if (intent.action != NotifyTargets.ACTION_SNOOZE) return
        val logId = intent.getLongExtra(NotifyTargets.EXTRA_LOG_ID, -1L)
        val minutes = intent.getIntExtra(NotifyTargets.EXTRA_MINUTES, Notifier.SNOOZE_MINUTES)
        if (logId < 0) return
        async(scope) { scheduler.snooze(logId, minutes) }
        // Remove the notification that was snoozed; its ID is the log ID.
        androidx.core.app.NotificationManagerCompat.from(context).cancel(logId.toInt())
    }
}
