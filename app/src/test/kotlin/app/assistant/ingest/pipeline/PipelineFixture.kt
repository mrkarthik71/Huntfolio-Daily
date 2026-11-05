package app.assistant.ingest.pipeline

import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.money.AssetParserRegistry
import app.assistant.data.money.InstructionPatternsJson
import app.assistant.data.repo.MoneyTestDb
import app.assistant.data.repo.RoomAtomicWork
import app.assistant.data.repo.RoomBillProposals
import app.assistant.data.repo.RoomIngestLedger
import app.assistant.data.repo.RoomMoneyAppList
import app.assistant.data.repo.RoomReviewQueueRepository
import app.assistant.data.repo.RoomSenderList
import app.assistant.data.repo.RoomTransactionRepository
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.SourceRef
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.domain.port.ReviewItem
import app.assistant.parse.money.BankFormat
import app.assistant.parse.money.ParserRegistry
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.SafeLog
import app.assistant.security.refs.FakeKeyManager
import app.assistant.security.refs.SourceRefFactory
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Counts how often the Message_Parser asked for formats, so a test can show a message never reached it (Property 2). */
internal class CountingRegistry(private val delegate: ParserRegistry) : ParserRegistry {
    val calls = AtomicInteger()

    override fun formatsFor(origin: String): List<BankFormat> {
        calls.incrementAndGet()
        return delegate.formatsFor(origin)
    }

    override val otpKeywords: List<String> get() = delegate.otpKeywords
}

/** What is stored after a run, without row ids, so two runs can be compared. */
internal data class Snapshot(val transactions: List<String>, val reviewItems: List<String>, val billProposals: List<String>, val factRefs: List<String>)

/**
 * The real pipeline over a real (in-memory) Room store, the shipped Parser_Registry and sender list, for the property
 * tests of task 20. [reset] empties it between iterations so one fixture serves a whole property.
 */
internal class PipelineFixture {
    val time = FakeTimeSource()
    val db: AppDatabase = MoneyTestDb.create()
    val events = CopyOnWriteArrayList<DiagnosticEvent>()
    val audit = IngestPipelineTest.RecordingAudit()
    val refs = SourceRefFactory(FakeKeyManager())
    val registryProbe: CountingRegistry
    val patterns: RedactionPatterns
    val transactions: RoomTransactionRepository
    val queue: RoomReviewQueueRepository
    val pipeline: IngestPipeline

    init {
        db.openHelper.writableDatabase.execSQL("INSERT INTO money_app (package_name) VALUES ('${MessageGen.UPI_PACKAGE}')")
        val provider = DbProvider { db }
        transactions = RoomTransactionRepository(provider, time, DuplicateWindowProvider { Duration.ofMinutes(WINDOW_MINUTES) })
        queue = RoomReviewQueueRepository(provider, time, transactions)
        val log = SafeLog(time).also { it.addSink { _, event -> events += event } }
        val ledger = RoomIngestLedger(provider)
        val work = RoomAtomicWork(provider)
        registryProbe = CountingRegistry(AssetParserRegistry(AssetParserRegistry.FILES) { File("src/main/assets/parser_registry/$it").inputStream() })
        patterns =
            (RedactionPatterns.of(InstructionPatternsJson.parse(File("src/main/assets/instruction_patterns.json").readText()).getOrThrow()) as Outcome.Ok).value
        pipeline = IngestPipeline(
            stages = listOf(
                SenderFilterStage(RoomSenderList(provider), RoomMoneyAppList(provider)),
                SourceRefStage(refs),
                ProcessedRefStage(ledger),
                ParseStage(registryProbe, time),
                OtpStage(ledger, time),
                FailedPaymentStage(ledger, time),
                ReviewQueueStage(queue, { patterns }, ledger, work, log, time),
                BillProposalStage(RoomBillProposals(provider), registryProbe, ledger, work, time),
                PersistStage(transactions, ledger, work, emptyList(), log, time),
            ),
            log = log,
            audit = audit,
            time = time,
        )
    }

    fun reset() {
        MoneyTestDb.reset(db)
        events.clear()
        audit.reads.clear()
        registryProbe.calls.set(0)
    }

    fun close() = db.close()

    fun ingest(message: RawMessage): IngestOutcome = runBlocking { pipeline.ingest(message) }

    fun transactionList(): List<Transaction> = runBlocking { transactions.transactions().first() }

    fun reviewItems(): List<ReviewItem> = runBlocking { queue.openItemsNewestFirst().first() }

    fun refOf(message: RawMessage): SourceRef = when (message) {
        is RawMessage.Sms -> (refs.sms(message.origin, message.sentAt, message.body) as Outcome.Ok).value
        is RawMessage.Notification -> (refs.notification(message.packageName, message.key, message.title, message.body) as Outcome.Ok).value
    }

    fun snapshot(): Snapshot {
        val sql = db.openHelper.writableDatabase
        fun rows(query: String, read: (android.database.Cursor) -> String) = sql.query(query).use { c ->
            generateSequence { if (c.moveToNext()) read(c) else null }.toList().sorted()
        }
        return Snapshot(
            transactions = transactionList().map {
                listOf(
                    it.type, it.amount, it.at, it.reference, it.account4, it.balance, it.merchantName, it.source, it.isReversal, it.category,
                    it.sourceRefs.map { r ->
                        r.value
                    }.sorted(),
                )
                    .joinToString("|")
            }.sorted(),
            reviewItems = reviewItems().map { "${it.sourceRef.value}|${it.redactedText}|${it.origin.value}" }.sorted(),
            billProposals = rows("SELECT merchant_id, amount_paise, due_date, status FROM bill_proposal") {
                "${it.getString(0)}|${it.getLong(1)}|${it.getString(2)}|${it.getString(3)}"
            },
            factRefs = rows("SELECT source_ref FROM parsed_fact") { it.getString(0) },
        )
    }

    private companion object {
        const val WINDOW_MINUTES = 15L
    }
}
