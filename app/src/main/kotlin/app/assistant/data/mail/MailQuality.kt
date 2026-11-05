package app.assistant.data.mail

import app.assistant.data.db.DatabaseHolder
import app.assistant.domain.port.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mail quality metrics for the Quality settings (Requirement 55.4, 58). Weekly Wrong_Mail_Alert and
 * notification totals over the last 8 weeks, daily counts over the last 7 days with the average, and
 * the 7-day window of fully-connected days. Read-only aggregates over `inbox_item`.
 */
@Singleton
class MailQuality @Inject constructor(private val holder: DatabaseHolder, private val time: TimeSource) {
    data class Metrics(
        val wrongAlertsLast8Weeks: Int,
        val notificationsLast8Weeks: Int,
        val wrongAlertsLast7Days: Int,
        val notificationsLast7Days: Int,
        val avgNotificationsPerDay: Double,
    )

    suspend fun metrics(): Metrics {
        val dao = holder.database().mailDao()
        val now = time.now().toEpochMilli()
        val eightWeeks = now - WEEK_MS * 8
        val sevenDays = now - DAY_MS * 7
        val notif7 = dao.notifiedCount(sevenDays)
        return Metrics(
            wrongAlertsLast8Weeks = dao.wrongAlertCount(eightWeeks),
            notificationsLast8Weeks = dao.notifiedCount(eightWeeks),
            wrongAlertsLast7Days = dao.wrongAlertCount(sevenDays),
            notificationsLast7Days = notif7,
            avgNotificationsPerDay = notif7 / DAYS_IN_WEEK,
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val WEEK_MS = DAY_MS * 7
        const val DAYS_IN_WEEK = 7.0
    }
}
