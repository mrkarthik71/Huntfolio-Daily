package app.assistant.data.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Periodic 24 h, flex 2 h (design: background work). A missed report catch-up is retried by WorkManager. */
@HiltWorker
class MaintenanceDailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val maintenance: MaintenanceDaily,
    private val jobRuns: app.assistant.data.health.JobRunRecorder,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = maintenance.run()
        jobRuns.record(UNIQUE_NAME)
        return if (result.reportsCaughtUp) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "maintenance-daily"
        private const val PERIOD_HOURS = 24L
        private const val FLEX_HOURS = 2L

        /** Registers the periodic job once; an existing registration is kept so its schedule is not reset on every start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceDailyWorker>(PERIOD_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS).build(),
            )
        }
    }
}
