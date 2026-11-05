package app.assistant.data.maintenance

import app.assistant.data.db.DbProvider
import app.assistant.domain.port.BillAlarmSync
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.SpendingReportRepository
import app.assistant.domain.port.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/** What one maintenance run found. The Audit_Log is append-only, so a large one is reported, never trimmed. */
data class MaintenanceResult(val reportsCaughtUp: Boolean, val auditEntryCount: Int, val auditLogLarge: Boolean)

/**
 * The daily maintenance job (design: `maintenance-daily`): creates any weekly report for a week that
 * ended while the app was closed, realigns the notification schedule, and checks the Audit_Log size.
 * Each step is independent, so one failing step never stops the others.
 */
@Singleton
class MaintenanceDaily @Inject constructor(
    private val reports: ReportRepository,
    private val spending: SpendingReportRepository,
    private val billAlarms: BillAlarmSync,
    private val scheduler: NotificationScheduling,
    private val subscriptions: app.assistant.domain.port.SubscriptionRepository,
    private val db: DbProvider,
    private val time: TimeSource,
) {
    suspend fun run(): MaintenanceResult {
        val caughtUp = runCatching { reports.ensureReportsUpTo(time.today()) }.isSuccess
        runCatching { spending.ensureReportsUpTo(time.today()) }
        // Syncing bill alarms also refreshes the schedule; the plain refresh runs when that step fails.
        if (runCatching { billAlarms.sync() }.isFailure) runCatching { scheduler.refresh() }
        // Subscription pass: missed-charge advance + "not seen" Suggestions, and usage-check due marking (Req 38.7, 39.1).
        runCatching { subscriptions.runDailyMaintenance() }
        val count = runCatching { db.database().auditDao().count() }.getOrDefault(0)
        return MaintenanceResult(caughtUp, count, count > AUDIT_LARGE_ROWS)
    }

    companion object {
        /** Above this many rows the Audit_Log is reported as large. */
        const val AUDIT_LARGE_ROWS = 50_000
    }
}
