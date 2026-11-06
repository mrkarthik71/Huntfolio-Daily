package app.assistant.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.assistant.R
import app.assistant.domain.model.NotificationType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Activity classes are named by string, so `notify` never imports `ui` (architecture rules). */
object NotifyTargets {
    const val MAIN = "app.assistant.ui.MainActivity"
    const val WHY = "app.assistant.ui.why.WhyActivity"
    const val EXTRA_ROUTE = "route"
    const val EXTRA_LOG_ID = "log_id"
    const val EXTRA_MINUTES = "minutes"
    const val ACTION_SNOOZE = "app.assistant.action.SNOOZE"
    const val ACTION_DONE = "app.assistant.action.DONE"
    const val EXTRA_REF = "ref"
}

/** [actionRef], when set, adds a "Done" button that applies to that item (a habit slot or a follow-up). */
data class NotificationContent(val title: String, val text: String, val route: String?, val actionRef: String? = null)

/** Creates one channel per Notification_Type and shows notifications that the governor allowed. */
@Singleton
class Notifier @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        for (type in NotificationType.entries) {
            nm.createNotificationChannel(
                NotificationChannel(channelId(type), context.getString(channelName(type)), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setShowBadge(false)
                },
            )
        }
    }

    /** False when the notification permission is missing or notifications are switched off. */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(logId: Long, type: NotificationType, content: NotificationContent) {
        if (!canPost()) return
        val id = logId.toInt()
        val open = Intent().setClassName(context.packageName, NotifyTargets.MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NotifyTargets.EXTRA_ROUTE, content.route)
        val why = Intent().setClassName(context.packageName, NotifyTargets.WHY).putExtra(NotifyTargets.EXTRA_LOG_ID, logId)
        val snooze = Intent(NotifyTargets.ACTION_SNOOZE).setPackage(context.packageName)
            .setClassName(context.packageName, "app.assistant.notify.NotificationActionReceiver")
            .putExtra(NotifyTargets.EXTRA_LOG_ID, logId).putExtra(NotifyTargets.EXTRA_MINUTES, SNOOZE_MINUTES)

        // The lock screen shows the type only (design: proposed default for the morning brief).
        val public = NotificationCompat.Builder(context, channelId(type))
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(context.getString(channelName(type)))
            .build()

        val n = NotificationCompat.Builder(context, channelId(type))
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id * 3, open, FLAGS))
            .addAction(0, context.getString(R.string.action_why), PendingIntent.getActivity(context, id * 3 + 1, why, FLAGS))
            .addAction(0, context.getString(R.string.action_snooze), PendingIntent.getBroadcast(context, id * 3 + 2, snooze, FLAGS))
            .let { b ->
                val ref = content.actionRef ?: return@let b
                val done = Intent(NotifyTargets.ACTION_DONE).setPackage(context.packageName)
                    .setClassName(context.packageName, "app.assistant.notify.NotificationActionReceiver")
                    .putExtra(NotifyTargets.EXTRA_LOG_ID, logId).putExtra(NotifyTargets.EXTRA_REF, ref)
                // The request code space after the three per-notification intents keeps Done distinct.
                b.addAction(0, context.getString(R.string.action_done), PendingIntent.getBroadcast(context, id * 3 + DONE_OFFSET, done, FLAGS))
            }
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val SNOOZE_MINUTES = 60
        private const val DONE_OFFSET = 1_000_000_000
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun channelId(type: NotificationType) = "type_${type.name.lowercase()}"

        fun channelName(type: NotificationType): Int = when (type) {
            NotificationType.MORNING_BRIEF -> R.string.channel_morning_brief
            NotificationType.EVENING_REVIEW -> R.string.channel_evening_review
            NotificationType.EVENT_REMINDER -> R.string.channel_event_reminder
            NotificationType.BILL_DUE -> R.string.channel_bill_due
            NotificationType.GOAL_NUDGE -> R.string.channel_goal_nudge
            NotificationType.IMPORTANT_MAIL -> R.string.channel_important_mail
            NotificationType.MAIL_DIGEST -> R.string.channel_mail_digest
            NotificationType.SUBSCRIPTION_RENEWAL -> R.string.channel_subscription_renewal
            NotificationType.SUBSCRIPTION_USAGE_CHECK -> R.string.channel_subscription_usage_check
            NotificationType.HEALTH_WARNING -> R.string.channel_health_warning
            NotificationType.IMPORTANT_DATE -> R.string.channel_important_date
            NotificationType.SMART_REMINDER -> R.string.channel_smart_reminder
            NotificationType.HABIT_CHECK -> R.string.channel_habit_check
            NotificationType.FOLLOW_UP -> R.string.channel_follow_up
            NotificationType.BUDGET_ALERT -> R.string.channel_budget_alert
            NotificationType.FOCUS_END -> R.string.channel_focus_end
            NotificationType.WEEKLY_PLAN -> R.string.channel_weekly_plan
            NotificationType.MONEY_DUE -> R.string.channel_money_due
        }
    }
}
