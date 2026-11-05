package app.assistant.data.mail

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The `mail-sync` periodic worker (Requirement 51.4). It runs the read-only [MailSync] loop on a
 * network-constrained schedule at the Mail_Sync_Interval. A sync error (including rejected
 * credentials) leaves the cursor untouched and simply waits for the next period — no data is cleared
 * and no retry storm is triggered.
 */
@HiltWorker
class MailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: MailSync,
    private val jobRuns: app.assistant.data.health.JobRunRecorder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { sync.sync() }
        jobRuns.record(UNIQUE_NAME)
        // Even on a sync error we return success: the loop handles its own state and the next period
        // retries. Returning retry() here would stack WorkManager backoff on top of the interval.
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "mail-sync"

        /** (Re)schedules the periodic sync at [intervalMinutes]; replaces an existing schedule. */
        fun schedule(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<MailSyncWorker>(intervalMinutes.coerceAtLeast(MIN_INTERVAL), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /** WorkManager's floor for periodic work is 15 minutes. */
        const val MIN_INTERVAL = 15L
    }
}
