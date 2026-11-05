package app.assistant.data.calendar

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.CalendarRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Refreshes the calendar cache (Requirement 22.2). Triggered by a content-URI change, a date or
 * zone change, a calendar-selection change, and on foreground. A failed read leaves the cache
 * unchanged and the worker simply retries on the next trigger — it never clears data.
 */
@HiltWorker
class CalendarRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CalendarRepository,
    private val scheduler: app.assistant.domain.port.NotificationScheduling,
    private val jobRuns: app.assistant.data.health.JobRunRecorder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (repository.refresh()) {
        is Outcome.Ok -> {
            // Event reminders are re-planned from the fresh cache (Requirement 24.5): a near event found by
            // this refresh gets its reminder within a minute even if the lead time has already passed.
            scheduler.refreshEventReminders()
            jobRuns.record(UNIQUE_NAME)
            Result.success()
        }
        // Transient provider/permission issues: let WorkManager retry; the cache is untouched.
        is Outcome.Err -> Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "calendar-refresh"

        /** Enqueues a single refresh, replacing any pending one so bursts of changes coalesce. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CalendarRefreshWorker>().build(),
            )
        }
    }
}
