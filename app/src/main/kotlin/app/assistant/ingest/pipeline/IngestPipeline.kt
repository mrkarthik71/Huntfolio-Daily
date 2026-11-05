package app.assistant.ingest.pipeline

import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.quality.ParseOutcomeKind
import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.TimeSource
import app.assistant.parse.money.MessageType
import app.assistant.parse.money.ParseResult
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.ErrorFingerprint
import app.assistant.security.log.IngestStage
import app.assistant.security.log.SafeLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** What happened to one Money_Message (design: IngestOutcome). */
enum class IngestOutcome { DROPPED_UNLISTED, DUPLICATE_REF, OTP, FAILED_PAYMENT, REVIEW_QUEUED, BILL_PROPOSED, TXN_CREATED, TXN_LINKED, TOMBSTONED, ERROR }

/**
 * A message as an adapter received it, in memory only. Its lifetime is one pipeline call: nothing stores it, caches it
 * or prints it, so [toString] hides the content (Requirement 3.2, 3.5).
 */
sealed interface RawMessage {
    /** The SMS sender ID or the app package name (Glossary: Message_Origin). */
    val origin: String
    val text: String
    val receivedAt: Instant
    val sourceKind: SourceKind

    /** An SMS, already joined from its parts (Requirement 26.7). [sentAt] is the PDU time that, with the sender and body, fixes the Source_Ref. */
    class Sms(override val origin: String, val body: String, val sentAt: Instant, override val receivedAt: Instant) : RawMessage {
        override val text: String get() = body
        override val sourceKind: SourceKind get() = SourceKind.SMS

        override fun toString(): String = "RawMessage.Sms"
    }

    /** A notification from an app in the Money_App_List. The Message_Text is the title and the text joined by one space (Requirement 27.1). */
    class Notification(val packageName: String, val key: String, val title: String, val body: String, override val receivedAt: Instant) : RawMessage {
        override val origin: String get() = packageName
        override val text: String get() = listOf(title, body).filter { it.isNotBlank() }.joinToString(" ")
        override val sourceKind: SourceKind get() = SourceKind.APP

        /** A notification with an empty title and empty text is discarded unread (Requirement 27.6). */
        val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank()

        override fun toString(): String = "RawMessage.Notification"
    }
}

/** What stays known about a message once its text has been dropped. */
data class MessageMeta(val kind: SourceKind, val origin: String, val receivedAt: Instant)

/**
 * What the stages pass along. The text is carried only until the parser has run, and afterwards only when the
 * Review_Queue needs it to redact: stages after "Parse" see fields, not text (design: ingest pipeline).
 */
data class IngestContext(val meta: MessageMeta, val message: RawMessage?, val ref: SourceRef? = null, val parse: ParseResult? = null) {
    override fun toString(): String = "IngestContext"
}

sealed interface StageResult {
    data class Continue(val ctx: IngestContext) : StageResult

    data class Stop(val outcome: IngestOutcome) : StageResult
}

interface PipelineStage {
    /** Named in the diagnostic event if this stage throws (Requirement 28.5). */
    val name: IngestStage

    suspend fun process(ctx: IngestContext): StageResult
}

/** How many messages ended each way in one batch (Requirement 26.5). */
data class BatchResult(val outcomes: Map<IngestOutcome, Int>) {
    val read: Int get() = outcomes.values.sum()

    operator fun get(outcome: IngestOutcome): Int = outcomes[outcome] ?: 0
}

/** What a reader needs from the pipeline: hand it a batch, get the outcomes. Lets reader logic be tested without the whole chain. */
interface MessageIngestor {
    /**
     * Ingests [messages] in order and writes one Audit_Log read entry for the whole batch, even when it is empty or the
     * read failed (Requirement 7.1). [readSucceeded] is whether the adapter's read of the source worked.
     */
    suspend fun ingestBatch(source: DataSource, messages: List<RawMessage>, readSucceeded: Boolean = true): BatchResult
}

/**
 * The chain of stages every Money_Message passes through (design: ingest pipeline). A stage that throws turns that
 * message into [IngestOutcome.ERROR] with a diagnostic event that names the stage and carries no content; the next
 * message is processed as usual (Requirement 28.5). Messages are handled one at a time, so a live SMS and the history
 * import cannot race on the same Source_Ref (Requirement 29.3).
 */
class IngestPipeline(
    private val stages: List<PipelineStage>,
    private val log: SafeLog,
    private val audit: AuditLog,
    private val time: TimeSource,
    private val quality: QualityMonitor? = null,
) : MessageIngestor {
    private val lock = Mutex()

    suspend fun ingest(message: RawMessage): IngestOutcome = lock.withLock { handle(message) }

    /**
     * Ingests [messages] in order and writes one Audit_Log read entry for the whole batch, even when it is empty or the
     * read failed (Requirement 7.1). [readSucceeded] is whether the adapter's read of the source worked.
     */
    override suspend fun ingestBatch(source: DataSource, messages: List<RawMessage>, readSucceeded: Boolean): BatchResult {
        val counts = LinkedHashMap<IngestOutcome, Int>()
        for (message in messages) counts.merge(ingest(message), 1, Int::plus)
        try {
            audit.recordRead(source, time.now(), messages.size, readSucceeded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.record(DiagnosticEvent.IngestStageFailed(IngestStage.AUDIT, ErrorFingerprint.of(e)))
        }
        return BatchResult(counts)
    }

    private suspend fun handle(message: RawMessage): IngestOutcome {
        var ctx = IngestContext(MessageMeta(message.sourceKind, message.origin, message.receivedAt), message)
        for (stage in stages) {
            val result = try {
                stage.process(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.record(DiagnosticEvent.IngestStageFailed(stage.name, ErrorFingerprint.of(e)))
                return IngestOutcome.ERROR
            }
            when (result) {
                is StageResult.Continue -> ctx = result.ctx
                is StageResult.Stop -> {
                    countForQuality(ctx, result.outcome)
                    return result.outcome
                }
            }
        }
        // Every message ends in a Stop; running out of stages means the stage list is incomplete.
        return IngestOutcome.ERROR
    }

    /**
     * Counts the message for the Quality_Monitor once it has ended in a way that stored or queued it, by its original
     * Received_Time (Requirement 36.1). A duplicate, an unlisted origin and an error are not counted, so a retry never counts twice.
     * A counter that cannot be written never changes the outcome.
     */
    private suspend fun countForQuality(ctx: IngestContext, outcome: IngestOutcome) {
        val monitor = quality ?: return
        val kind = when (outcome) {
            IngestOutcome.TXN_CREATED, IngestOutcome.TXN_LINKED -> when ((ctx.parse as? ParseResult.Parsed)?.message?.type) {
                MessageType.DEBIT -> ParseOutcomeKind.DEBIT
                MessageType.CREDIT -> ParseOutcomeKind.CREDIT
                MessageType.BILL, null -> return
            }
            IngestOutcome.BILL_PROPOSED -> ParseOutcomeKind.BILL
            IngestOutcome.FAILED_PAYMENT -> ParseOutcomeKind.FAILED_PAYMENT
            IngestOutcome.REVIEW_QUEUED -> ParseOutcomeKind.REVIEW
            IngestOutcome.OTP -> ParseOutcomeKind.OTP
            IngestOutcome.DROPPED_UNLISTED, IngestOutcome.DUPLICATE_REF, IngestOutcome.TOMBSTONED, IngestOutcome.ERROR -> return
        }
        try {
            monitor.recordParse(MessageOrigin(ctx.meta.origin), ctx.meta.receivedAt.atZone(time.zone()).toLocalDate(), kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.record(DiagnosticEvent.IngestStageFailed(IngestStage.QUALITY, ErrorFingerprint.of(e)))
        }
    }
}
