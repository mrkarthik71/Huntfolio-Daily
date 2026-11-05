package app.assistant.ingest.pipeline

import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.engine.redact.Redactor
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.AtomicWork
import app.assistant.domain.port.BillProposals
import app.assistant.domain.port.IngestLedger
import app.assistant.domain.port.ReviewAddResult
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import app.assistant.parse.money.FieldName
import app.assistant.parse.money.MessageType
import app.assistant.parse.money.ParseResult
import app.assistant.parse.money.ParserRegistry
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.ErrorFingerprint
import app.assistant.security.log.IngestStage
import app.assistant.security.log.SafeLog
import kotlin.coroutines.cancellation.CancellationException

/** Where the Redactor's configuration comes from. Null means it could not be built, and then nothing is shown (fail closed). */
fun interface RedactionPatternsProvider {
    fun patterns(): RedactionPatterns?
}

/**
 * Puts an unreadable message in the Review_Queue with its text run through the Redactor first (Requirements 4.3, 30.1).
 * If the text cannot be redacted safely, the item is still made so the User knows a message needs a look, but it holds a
 * fixed notice instead of any of the message.
 */
class ReviewQueueStage(
    private val queue: ReviewQueueRepository,
    private val patterns: RedactionPatternsProvider,
    private val ledger: IngestLedger,
    private val work: AtomicWork,
    private val log: SafeLog,
    private val time: TimeSource,
) : PipelineStage {
    override val name = IngestStage.REVIEW_QUEUE

    override suspend fun process(ctx: IngestContext): StageResult {
        if (ctx.parse !is ParseResult.Unknown) return StageResult.Continue(ctx)
        val message = checkNotNull(ctx.message) { "An unreadable message keeps its text for redaction" }
        val ref = checkNotNull(ctx.ref)
        log.record(DiagnosticEvent.ParserMiss(null))
        val redacted = patterns.patterns()?.let { Redactor.redact(message.text, it) }
        val text = if (redacted is Outcome.Ok) redacted.value else UNREADABLE_NOTICE
        val added = work.run {
            queue.add(ref, text, MessageOrigin(message.origin), message.receivedAt).also {
                if (it != ReviewAddResult.TOMBSTONED) ledger.markProcessed(ref, OUTCOME, time.now())
            }
        }
        return StageResult.Stop(if (added == ReviewAddResult.TOMBSTONED) IngestOutcome.TOMBSTONED else IngestOutcome.REVIEW_QUEUED)
    }

    companion object {
        /** What an item holds when the message could not be redacted safely. */
        const val UNREADABLE_NOTICE = "[Text hidden: this message could not be made safe to show. Check your messages for it.]"
        private const val OUTCOME = "review_queued"
    }
}

/** Proposes a Bill from a bill message, unless the same Bill or an open proposal exists (Requirements 34.2, 34.8). */
class BillProposalStage(
    private val bills: BillProposals,
    private val registry: ParserRegistry,
    private val ledger: IngestLedger,
    private val work: AtomicWork,
    private val time: TimeSource,
) : PipelineStage {
    override val name = IngestStage.BILL_PROPOSAL

    override suspend fun process(ctx: IngestContext): StageResult {
        val parsed = ctx.parse as? ParseResult.Parsed ?: return StageResult.Continue(ctx)
        if (parsed.message.type != MessageType.BILL) return StageResult.Continue(ctx)
        val ref = checkNotNull(ctx.ref)
        // A bill message the format gives no due date for leaves the date empty for the User, rather than using the received date.
        val hasDueDate = registry.formatsFor(ctx.meta.origin).firstOrNull { it.id == parsed.formatId }?.template?.fields?.contains(FieldName.DUE_DATE) == true
        val dueDate = if (hasDueDate) parsed.message.at.toLocalDate() else null
        work.run {
            bills.propose(parsed.message.merchantOrVpa, parsed.message.amount, dueDate)
            ledger.markProcessed(ref, OUTCOME, time.now())
        }
        return StageResult.Stop(IngestOutcome.BILL_PROPOSED)
    }

    private companion object {
        const val OUTCOME = "bill_proposed"
    }
}

/** What a post-commit hook is told about a message that has just been stored. */
data class PersistedMessage(val transaction: TransactionId, val created: Boolean, val origin: String, val formatId: String)

/** Work that follows a stored message: the Bill paid matcher, Subscription_Detector and Quality_Monitor plug in here. */
fun interface PostCommitHook {
    suspend fun onPersisted(event: PersistedMessage)
}

/**
 * Stores a debit or credit. The Deduplicator and Categoriser run inside the repository, in one Room transaction with the
 * Source_Ref link and the Merchant; the message is recorded as handled in that same transaction. Hooks run after the
 * commit, and one that fails never changes the outcome or stops the others.
 */
class PersistStage(
    private val transactions: TransactionRepository,
    private val ledger: IngestLedger,
    private val work: AtomicWork,
    private val hooks: Collection<PostCommitHook>,
    private val log: SafeLog,
    private val time: TimeSource,
) : PipelineStage {
    override val name = IngestStage.PERSIST

    override suspend fun process(ctx: IngestContext): StageResult {
        val parsed = ctx.parse as? ParseResult.Parsed ?: return StageResult.Continue(ctx)
        val type = when (parsed.message.type) {
            MessageType.DEBIT -> TxnType.DEBIT
            MessageType.CREDIT -> TxnType.CREDIT
            MessageType.BILL -> return StageResult.Continue(ctx)
        }
        val ref = checkNotNull(ctx.ref)
        val m = parsed.message
        val fact = ParsedFact(
            sourceRef = ref,
            origin = MessageOrigin(ctx.meta.origin),
            sourceKind = ctx.meta.kind,
            type = type,
            amount = m.amount,
            at = m.at.atZone(time.zone()).toInstant(),
            receivedAt = ctx.meta.receivedAt,
            reference = m.reference,
            account4 = m.account4,
            merchantOrVpa = m.merchantOrVpa,
            balance = m.balance,
            isReversal = m.isReversal,
        )
        val stored = work.run {
            transactions.ingest(fact, parsed.formatId).also {
                if (it is Outcome.Ok) ledger.markProcessed(ref, if (it.value.created) OUTCOME_CREATED else OUTCOME_LINKED, time.now())
            }
        }
        if (stored is Outcome.Err) return StageResult.Stop(IngestOutcome.TOMBSTONED)
        val result = (stored as Outcome.Ok).value
        runHooks(PersistedMessage(result.transaction, result.created, ctx.meta.origin, parsed.formatId))
        return StageResult.Stop(if (result.created) IngestOutcome.TXN_CREATED else IngestOutcome.TXN_LINKED)
    }

    private suspend fun runHooks(event: PersistedMessage) {
        for (hook in hooks) {
            try {
                hook.onPersisted(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.record(DiagnosticEvent.IngestStageFailed(IngestStage.HOOKS, ErrorFingerprint.of(e)))
            }
        }
    }

    private companion object {
        const val OUTCOME_CREATED = "txn_created"
        const val OUTCOME_LINKED = "txn_linked"
    }
}
