package app.assistant.ingest.sms

import app.assistant.domain.model.DataSource
import app.assistant.domain.port.SenderList
import app.assistant.domain.port.TimeSource
import app.assistant.ingest.SenderMatcher
import app.assistant.ingest.pipeline.BatchResult
import app.assistant.ingest.pipeline.IngestOutcome
import app.assistant.ingest.pipeline.MessageIngestor
import app.assistant.ingest.pipeline.RawMessage
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** The result of one read of the inbox: what happened, and what the pipeline made of the messages. */
data class ReadReport(val status: ReadStatus, val batch: BatchResult = BatchResult(emptyMap()))

/**
 * The logic of the Sms_Reader without the Android parts: live messages, catch-up after the process was stopped, and the
 * history import (Requirements 26.1, 26.2, 26.5, 26.6). The receiver and the workers of the withSms build call this; the
 * inbox, the cursor and the pipeline come in as ports so it can be tested without a phone.
 *
 * Only messages from listed senders are ever read: the inbox is given the sender test, and a live message from anyone else
 * is dropped here before the pipeline, leaving no Audit_Log entry either (Requirement 26.3).
 */
class SmsReaderCore(
    private val inbox: SmsInbox,
    private val senders: SenderList,
    private val cursor: SmsCursor,
    private val ingestor: MessageIngestor,
    private val time: TimeSource,
) {
    /** A live SMS (already joined from its parts). Null when the sender is not listed, in which case nothing at all happens. */
    suspend fun onLive(message: RawMessage.Sms): BatchResult? {
        if (!SenderMatcher.matches(message.origin, senders.entries())) return null
        val batch = ingestor.ingestBatch(DataSource.SMS, listOf(message), readSucceeded = true)
        advance(message.receivedAt, batch)
        return batch
    }

    /**
     * Reads the inbox from where [SmsReaderRules.catchUpSince] says (Requirement 26.6). Does nothing, and writes no audit
     * entry, when reading is not enabled ([enabledAt] null).
     */
    suspend fun catchUp(enabledAt: Instant?): ReadReport {
        val since = SmsReaderRules.catchUpSince(cursor.lastReceivedAt(), enabledAt) ?: return ReadReport(ReadStatus.NOTHING_TO_READ)
        return readInbox(since)
    }

    /** The one-off import of the last three months after the User accepts it (Requirements 26.2, 26.5). */
    suspend fun historyImport(enabledOn: LocalDate): ImportSummary {
        val report = readInbox(SmsReaderRules.historyImportStart(enabledOn, time.zone()))
        return ImportSummary.of(report.status, report.batch)
    }

    /** Reads every earlier SMS from listed senders, when the User asks for all of them. Safe to repeat: ingest is idempotent. */
    suspend fun importAll(): ImportSummary {
        val report = readInbox(Instant.EPOCH)
        return ImportSummary.of(report.status, report.batch)
    }

    private suspend fun readInbox(start: Instant): ReadReport {
        val listed = senders.entries()
        val rows = try {
            inbox.since(start) { SenderMatcher.matches(it, listed) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed read is still a read the Audit_Log records (Requirement 7.1). Nothing about the failure is kept.
            ingestor.ingestBatch(DataSource.SMS, emptyList(), readSucceeded = false)
            return ReadReport(ReadStatus.FAILED)
        }
        val messages = rows.sortedBy { it.dateReceived }.mapNotNull { SmsAssembler.fromInbox(it) }
        val batch = ingestor.ingestBatch(DataSource.SMS, messages, readSucceeded = true)
        messages.lastOrNull()?.let { advance(it.receivedAt, batch) }
        return ReadReport(if (messages.isEmpty()) ReadStatus.NOTHING_TO_READ else ReadStatus.READ, batch)
    }

    /**
     * Moves the cursor to [to] only when every message in the batch was handled and it would move forward. A batch with an
     * error leaves the cursor where it was, so the next catch-up reads the same messages again (safe, since ingest is
     * idempotent) instead of losing the one that failed.
     */
    private suspend fun advance(to: Instant, batch: BatchResult) {
        if (batch[IngestOutcome.ERROR] > 0) return
        val last = cursor.lastReceivedAt()
        if (last == null || to > last) cursor.advanceTo(to)
    }
}
