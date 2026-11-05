package app.assistant.ingest.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.assistant.R
import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.HistoryImportSummary
import app.assistant.domain.port.ReaderStateStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The one-time SMS_History_Import (Requirements 26.2, 26.5). It is a foreground (dataSync) worker so a long read of three
 * months of SMS is not killed; it carries no Message_Text in its data. It runs [SmsReaderCore.historyImport], marks the
 * offer answered, and publishes the counts through [SmsImportReporter] for the money settings to show. The import is
 * offered once; this worker runs only after the User accepts.
 */
@HiltWorker
class SmsHistoryImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val core: SmsReaderCore,
    private val readerState: ReaderStateStore,
    private val reporter: SmsImportReporter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flags = readerState.current()
        if (!flags.smsEnabled) return Result.success()
        reporter.state.value = HistoryImportState.Running
        val enabledOn = (flags.smsEnabledAt ?: flags.smsCursor)?.let { java.time.LocalDate.ofInstant(it, java.time.ZoneId.systemDefault()) }
            ?: java.time.LocalDate.now()
        val summary = core.historyImport(enabledOn)
        readerState.markHistoryImportOffered()
        reporter.state.value = HistoryImportState.Done(
            HistoryImportSummary(smsRead = summary.smsRead, transactionsCreated = summary.transactionsCreated, reviewItems = summary.reviewItemsAdded),
        )
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.sms_import_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.sms_import_notification_title))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "sms-history-import"
        private const val CHANNEL_ID = "sms-history-import"
        private const val NOTIFICATION_ID = 920_001

        /** Starts the one-time import after the User accepts the offer. KEEP so a re-request does not start a second run. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmsHistoryImportWorker>().build(),
            )
        }
    }
}
