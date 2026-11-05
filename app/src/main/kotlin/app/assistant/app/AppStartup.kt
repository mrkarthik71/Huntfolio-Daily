package app.assistant.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.EncryptedStoreGate
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.StoreStatus
import app.assistant.domain.port.TimeSource
import app.assistant.notify.Notifier
import app.assistant.widget.TodayWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time start work (design: app start). Notification channels are created every launch, and the
 * scheduler is realigned with the current settings once the Encrypted_Store is open, so a time the
 * User changed while the app was gone takes effect and any report for a week that ended is created
 * (Requirements 17.6, 18.9, 20.7). It runs off the main thread and never blocks the first frame.
 */
@Singleton
class AppStartup @Inject constructor(
    private val notifier: Notifier,
    private val scheduler: NotificationScheduling,
    private val billAlarms: app.assistant.domain.port.BillAlarmSync,
    private val reports: ReportRepository,
    private val plans: PlanRepository,
    private val calendarSource: app.assistant.domain.port.CalendarSource,
    private val store: EncryptedStoreGate,
    private val dispatchers: DispatcherProvider,
    private val time: TimeSource,
    private val reviewQueue: app.assistant.domain.port.ReviewQueueRepository,
    private val smsFallback: app.assistant.data.ai.SmsFallbackParser,
    private val backup: app.assistant.domain.port.BackupManager,
    private val reminders: app.assistant.domain.port.ReminderScheduler,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun run() {
        // Channels need no store, so they are created immediately.
        notifier.createChannels()
        scope.launch {
            withContext(dispatchers.io) {
                // Finish an interrupted restore before the store is opened (Requirement 60.7).
                runCatching { backup.recoverIfInterrupted() }
                // Wait until the store is open; it never opens while the keystore is unavailable.
                val status = store.status.first { it !is StoreStatus.NotOpened }
                if (status is StoreStatus.Open) {
                    runCatching { reports.ensureReportsUpTo(time.today()) }
                    runCatching { scheduler.refresh() }
                    runCatching { billAlarms.sync() }
                    app.assistant.data.maintenance.MaintenanceDailyWorker.schedule(context)
                    app.assistant.data.health.HealthCheckWorker.schedule(context)
                    app.assistant.data.planner.ProactiveWorker.schedule(context)
                    runCatching { reminders.sync() }
                    observeWidgetData()
                    observeReviewQueueForFallback()
                    if (FeatureFlags.CALENDAR) startCalendarRefresh()
                }
            }
        }
    }

    /**
     * Refreshes the calendar cache on start and whenever the provider signals a change
     * (Requirement 22.2) — but only once the User has granted READ_CALENDAR, so a fresh install
     * never reads the provider without permission. The worker leaves the cache untouched on
     * failure, so this is safe to fire freely; it coalesces bursts via unique work.
     */
    private fun startCalendarRefresh() {
        if (!hasCalendarPermission()) return
        app.assistant.data.calendar.CalendarRefreshWorker.enqueue(context)
        calendarSource.changes
            .onEach { if (hasCalendarPermission()) app.assistant.data.calendar.CalendarRefreshWorker.enqueue(context) }
            .launchIn(scope)
    }

    private fun hasCalendarPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Redraw the home-screen widget whenever today's priorities change (Requirement: Today_Widget stays current). */
    private fun observeWidgetData() {
        plans.planFor(time.today())
            .map { it?.priorities?.map { task -> task.id.value to (task.status to task.title) } }
            .distinctUntilChanged()
            .onEach { runCatching { TodayWidget().updateAll(context) } }
            .launchIn(scope)
    }

    /**
     * Runs the SMS fallback parser once for each new Review_Queue item (Requirement 49.1). The parser
     * is off by default and checks `fallbackAttempted` itself, so this observer is inert until the
     * user turns it on and never re-attempts an item it already tried.
     */
    private fun observeReviewQueueForFallback() {
        reviewQueue.openItemsNewestFirst()
            .onEach { items ->
                items.filterNot { it.fallbackAttempted }.forEach { item ->
                    runCatching { smsFallback.attempt(item.id) }
                }
            }
            .launchIn(scope)
    }
}
