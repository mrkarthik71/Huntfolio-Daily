package app.assistant.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.assistant.domain.port.QueuedAlarm
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** What [AlarmArmer.arm] did with the system alarm. */
enum class ArmResult { CANCELLED, EXACT, INEXACT }

/**
 * Keeps exactly one system alarm: the earliest pending row (ADR 0009). With Exact_Alarm_Access it
 * is exact and fires in Doze; without it, an inexact alarm that may be late (Requirement 19.3).
 */
@Singleton
class AlarmArmer @Inject constructor(@param:ApplicationContext private val context: Context, private val time: TimeSource) {
    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun hasExactAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Arms (or cancels) the single system alarm and reports which kind it was, so callers and tests can tell exact from inexact. */
    fun arm(next: QueuedAlarm?): ArmResult {
        val pending = pendingIntent()
        if (next == null) {
            alarmManager.cancel(pending)
            return ArmResult.CANCELLED
        }
        // A row that is already due fires one second from now, never in the past.
        val at = maxOf(next.dueAt, time.now().plus(Duration.ofSeconds(1))).toEpochMilli()
        return try {
            if (hasExactAccess()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                ArmResult.EXACT
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                ArmResult.INEXACT
            }
        } catch (e: SecurityException) {
            // The exact-alarm permission was revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            ArmResult.INEXACT
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION).setClassName(context.packageName, "app.assistant.notify.AlarmReceiver"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION = "app.assistant.action.ALARM"
    }
}
