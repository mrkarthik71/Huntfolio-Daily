package app.assistant.ingest.pipeline

import android.app.Application
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
import app.assistant.domain.engine.quality.ParseOutcomeKind
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.IngestStage
import app.assistant.security.log.SafeLog
import app.assistant.security.refs.FakeKeyManager
import app.assistant.security.refs.SourceRefFactory
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.RecordingQualityMonitor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The pipeline tells the Quality_Monitor how each message ended, by its Received_Time (Requirement 36.1): stored and queued
 * messages count once, while duplicates, unlisted origins and errors count nothing, and a failing counter never changes the outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QualityRecordingTest {
    private lateinit var db: AppDatabase
    private lateinit var pipeline: IngestPipeline
    private val time = FakeTimeSource()
    private val events = mutableListOf<DiagnosticEvent>()
    private var monitor = RecordingQualityMonitor()

    // 14:06 IST on 5 January 2025.
    private val t0: Instant = Instant.parse("2025-01-05T08:36:00Z")
    private val day = LocalDate.of(2025, 1, 5)

    private val debitSms = "Rs 250.00 debited from a/c 1234 on 05-01-2025 14:05 at Swiggy. Avl Bal Rs 10,000.00"
    private val creditSms = "Rs 1,000.00 credited to a/c 1234 on 06-01-2025 09:00. Ref ABC123. Avl Bal Rs 11,000.00"
    private val billSms = "Your BankA Credit Card bill of Rs 4,500.00 is due on 20-01-2025. Pay in the BankA app."
    private val otpSms = "482913 is your OTP for a transaction of Rs 250.00 at Swiggy. Valid for 10 mins."
    private val failedSms = "Your transaction of Rs 250.00 at Swiggy on 05-01-2025 has failed/declined. Ref XYZ987."
    private val unknownSms = "Dear Asha Menon your statement is ready. Call 9876543210 or open https://bank.example/statement today"

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        build(monitor)
    }

    private fun build(quality: RecordingQualityMonitor) {
        monitor = quality
        val provider = DbProvider { db }
        val transactions = RoomTransactionRepository(provider, time, DuplicateWindowProvider { Duration.ofMinutes(15) })
        val queue = RoomReviewQueueRepository(provider, time, transactions)
        val log = SafeLog(time).also { it.addSink { _, event -> events += event } }
        val ledger = RoomIngestLedger(provider)
        val work = RoomAtomicWork(provider)
        val registry = AssetParserRegistry(AssetParserRegistry.FILES) { File("src/main/assets/parser_registry/$it").inputStream() }
        val patterns = (
            RedactionPatterns.of(
                InstructionPatternsJson.parse(File("src/main/assets/instruction_patterns.json").readText()).getOrThrow(),
            ) as Outcome.Ok
            ).value
        pipeline = IngestPipeline(
            stages = listOf(
                SenderFilterStage(RoomSenderList(provider), RoomMoneyAppList(provider)),
                SourceRefStage(SourceRefFactory(FakeKeyManager())),
                ProcessedRefStage(ledger),
                ParseStage(registry, time),
                OtpStage(ledger, time),
                FailedPaymentStage(ledger, time),
                ReviewQueueStage(queue, { patterns }, ledger, work, log, time),
                BillProposalStage(RoomBillProposals(provider), registry, ledger, work, time),
                PersistStage(transactions, ledger, work, emptyList(), log, time),
            ),
            log = log,
            audit = IngestPipelineTest.RecordingAudit(),
            time = time,
            quality = quality,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun sms(body: String, sender: String = "AX-BANKA-S", at: Instant = t0) = RawMessage.Sms(sender, body, at, at)

    private fun ingest(message: RawMessage): IngestOutcome = runBlocking { pipeline.ingest(message) }

    private fun kinds() = monitor.parses.map { it.kind }

    @Test
    fun `each kind of ending is counted once with its kind`() {
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(debitSms)))
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(creditSms, at = t0.plusSeconds(3_600))))
        assertEquals(IngestOutcome.BILL_PROPOSED, ingest(sms(billSms, at = t0.plusSeconds(7_200))))
        assertEquals(IngestOutcome.OTP, ingest(sms(otpSms, at = t0.plusSeconds(10_800))))
        assertEquals(IngestOutcome.FAILED_PAYMENT, ingest(sms(failedSms, at = t0.plusSeconds(14_400))))
        assertEquals(IngestOutcome.REVIEW_QUEUED, ingest(sms(unknownSms, at = t0.plusSeconds(18_000))))
        assertEquals(
            listOf(
                ParseOutcomeKind.DEBIT,
                ParseOutcomeKind.CREDIT,
                ParseOutcomeKind.BILL,
                ParseOutcomeKind.OTP,
                ParseOutcomeKind.FAILED_PAYMENT,
                ParseOutcomeKind.REVIEW,
            ),
            kinds(),
        )
        assertTrue(monitor.parses.all { it.origin == "AX-BANKA-S" })
    }

    @Test
    fun `a message read twice is counted once`() {
        ingest(sms(debitSms))
        assertEquals(IngestOutcome.DUPLICATE_REF, ingest(sms(debitSms)))
        assertEquals(listOf(ParseOutcomeKind.DEBIT), kinds())
    }

    @Test
    fun `an unlisted origin counts nothing`() {
        assertEquals(IngestOutcome.DROPPED_UNLISTED, ingest(sms(debitSms, sender = "XX-NOBANK")))
        assertTrue(monitor.parses.isEmpty())
    }

    @Test
    fun `the day is the Received_Time day, not the date inside the message`() {
        // 00:30 IST on 6 January, while the message text says 5 January.
        ingest(sms(debitSms, at = Instant.parse("2025-01-05T19:00:00Z")))
        assertEquals(LocalDate.of(2025, 1, 6), monitor.parses.single().day)
        ingest(sms(creditSms, at = t0))
        assertEquals(day, monitor.parses.last().day)
    }

    @Test
    fun `a counter that fails never changes the outcome and leaves a content free event`() {
        build(RecordingQualityMonitor(fails = true))
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(debitSms)))
        val failure = events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().single()
        assertEquals(IngestStage.QUALITY, failure.stage)
    }
}
