package app.assistant.ingest.sms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.assistant.domain.port.ReaderStateStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reads the Bank_Sender_List SMS that arrived while the process was stopped (Requirement 26.6), run on process start and
 * after a reboot. It carries IDs and enum names only — never Message_Text — in its WorkManager data (it carries none at
 * all). Nothing happens when SMS reading is off; the core reads only listed senders and audits a failed read.
 */
@HiltWorker
class SmsCatchupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val core: SmsReaderCore,
    private val readerState: ReaderStateStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flags = readerState.current()
        if (!flags.smsEnabled) return Result.success()
        return when (core.catchUp(flags.smsEnabledAt).status) {
            ReadStatus.FAILED -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "sms-catchup"

        /** Enqueues a single catch-up, replacing any pending one so bursts of starts coalesce. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmsCatchupWorker>().build(),
            )
        }
    }
}
