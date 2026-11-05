package app.assistant.data.health

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.assistant.domain.model.NotificationType
import app.assistant.notify.NotificationContent
import app.assistant.notify.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The `health-check` periodic worker (Requirement 62.1, 62.2). It runs [HealthCheck] and, when a
 * warning is due, raises ONE combined notification (fix actions + Battery_Guide link live on the
 * in-app warning the notification opens). It never nags: a dismissed set stays quiet until a new
 * problem appears.
 */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val healthCheck: HealthCheck,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching {
            if (healthCheck.shouldWarn()) {
                notifier.post(
                    logId = HEALTH_LOG_ID,
                    type = NotificationType.HEALTH_WARNING,
                    content = NotificationContent(
                        title = "Something needs your attention",
                        text = "The assistant can't run some of its background work. Tap to fix.",
                        route = "settings/health",
                    ),
                )
            }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "health-check"
        private const val HEALTH_LOG_ID = -42L
        private const val INTERVAL_HOURS = 12L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
