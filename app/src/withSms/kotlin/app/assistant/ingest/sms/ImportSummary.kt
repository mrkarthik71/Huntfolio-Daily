package app.assistant.ingest.sms

import app.assistant.ingest.pipeline.BatchResult
import app.assistant.ingest.pipeline.IngestOutcome
import java.time.LocalDate

enum class ReadStatus {
    /** Reading was on, but the inbox held nothing to read. */
    NOTHING_TO_READ,

    READ,

    /** The inbox could not be read, for example because the permission was withdrawn. */
    FAILED,
}

/**
 * What the SMS_History_Import shows when it finishes: how many SMS were read, how many Transactions it created and how many
 * items it added to the Review_Queue (Requirement 26.5). A message joining a Transaction that already exists is read but
 * creates nothing.
 */
data class ImportSummary(val status: ReadStatus, val smsRead: Int, val transactionsCreated: Int, val reviewItemsAdded: Int) {
    companion object {
        fun of(status: ReadStatus, batch: BatchResult) = ImportSummary(status, batch.read, batch[IngestOutcome.TXN_CREATED], batch[IngestOutcome.REVIEW_QUEUED])
    }
}

enum class SmsWorkKind { CATCH_UP, HISTORY_IMPORT }

/**
 * Everything a background SMS job is told. WorkManager keeps its input data on disk, so this holds an enum name and a date
 * and nothing else: no sender, no message text, no Source_Ref (Requirements 3.2, 26.6). [enabledOn] is set for the import only.
 */
data class SmsWorkInput(val kind: SmsWorkKind, val enabledOn: LocalDate? = null) {
    fun toMap(): Map<String, String> = buildMap {
        put(KEY_KIND, kind.name)
        enabledOn?.let { put(KEY_ENABLED_ON, it.toString()) }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_ENABLED_ON = "enabled_on"

        /** Null for anything that is not an input this class wrote; a job given such data does nothing. */
        fun from(data: Map<String, String>): SmsWorkInput? {
            val kind = SmsWorkKind.entries.firstOrNull { it.name == data[KEY_KIND] } ?: return null
            val enabledOn = data[KEY_ENABLED_ON]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return null }
            return if (kind == SmsWorkKind.HISTORY_IMPORT && enabledOn == null) null else SmsWorkInput(kind, enabledOn)
        }
    }
}
