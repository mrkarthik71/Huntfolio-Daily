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
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.domain.port.PersistedMessage
import app.assistant.domain.port.PostCommitHook
import app.assistant.domain.port.ReviewItem
import app.assistant.security.keys.KeyError
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.IngestStage
import app.assistant.security.log.SafeLog
import app.assistant.security.refs.FakeKeyManager
import app.assistant.security.refs.SourceRefFactory
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The whole chain against real stores and the shipped Parser_Registry, sender list and Redactor patterns (task 20.6).
 * The formats are the synthetic placeholders: BankA and BankB SMS, and one UPI app's notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MoneyIngestIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var transactions: RoomTransactionRepository
    private lateinit var queue: RoomReviewQueueRepository
    private lateinit var pipeline: IngestPipeline
    private val time = FakeTimeSource()
    private val keys = FakeKeyManager()
    private val events = mutableListOf<DiagnosticEvent>()
    private val audit = IngestPipelineTest.RecordingAudit()
    private val hookCalls = mutableListOf<PersistedMessage>()
    private var hookFails = false

    // 14:06 IST, a minute after the SMS below says the payment happened.
    private val t0: Instant = Instant.parse("2025-01-05T08:36:00Z")

    private val debitSms = "Rs 250.00 debited from a/c 1234 on 05-01-2025 14:05 at Swiggy. Avl Bal Rs 10,000.00"
    private val upiDebit = "Paid Rs 250.00 to swiggy@icici on 2025-01-05 at 14:06. UPI Ref 123456789012."
    private val creditSms = "Rs 1,000.00 credited to a/c 1234 on 06-01-2025 09:00. Ref ABC123. Avl Bal Rs 11,000.00"
    private val billSms = "Your BankA Credit Card bill of Rs 4,500.00 is due on 20-01-2025. Pay in the BankA app."
    private val otpSms = "482913 is your OTP for a transaction of Rs 250.00 at Swiggy. Valid for 10 mins."
    private val failedSms = "Your transaction of Rs 250.00 at Swiggy on 05-01-2025 has failed/declined. Ref XYZ987."
    private val unknownSms = "Dear Asha Menon your statement is ready. Call 9876543210 or open https://bank.example/statement today"

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        db.openHelper.writableDatabase.execSQL("INSERT INTO money_app (package_name) VALUES ('com.example.upiapp')")
        val provider = DbProvider { db }
        transactions = RoomTransactionRepository(provider, time, DuplicateWindowProvider { Duration.ofMinutes(15) })
        queue = RoomReviewQueueRepository(provider, time, transactions)
        val log = SafeLog(time).also { it.addSink { _, event -> events += event } }
        val ledger = RoomIngestLedger(provider)
        val work = RoomAtomicWork(provider)
        val registry = AssetParserRegistry(AssetParserRegistry.FILES) { File("src/main/assets/parser_registry/$it").inputStream() }
        val patterns = (
            RedactionPatterns.of(
                InstructionPatternsJson.parse(File("src/main/assets/instruction_patterns.json").readText()).getOrThrow(),
            ) as Outcome.Ok
            ).value
        val hook = PostCommitHook { event ->
            hookCalls += event
            if (hookFails) error("hook failed")
        }
        pipeline = IngestPipeline(
            stages = listOf(
                SenderFilterStage(RoomSenderList(provider), RoomMoneyAppList(provider)),
                SourceRefStage(SourceRefFactory(keys)),
                ProcessedRefStage(ledger),
                ParseStage(registry, time),
                OtpStage(ledger, time),
                FailedPaymentStage(ledger, time),
                ReviewQueueStage(queue, { patterns }, ledger, work, log, time),
                BillProposalStage(RoomBillProposals(provider), registry, ledger, work, time),
                PersistStage(transactions, ledger, work, listOf(hook), log, time),
            ),
            log = log,
            audit = audit,
            time = time,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun sms(body: String, sender: String = "AX-BANKA-S", sentAt: Instant = t0) = RawMessage.Sms(sender, body, sentAt, sentAt)

    private fun notification(
        body: String,
        receivedAt: Instant = t0.plusSeconds(60),
        key: String = "k1",
        pkg: String = "com.example.upiapp",
        title: String = "",
    ) = RawMessage.Notification(pkg, key, title, body, receivedAt)

    private fun ingest(message: RawMessage): IngestOutcome = runBlocking { pipeline.ingest(message) }

    private fun txns(): List<Transaction> = runBlocking { transactions.transactions().first() }

    private fun items(): List<ReviewItem> = runBlocking { queue.openItemsNewestFirst().first() }

    private fun processedOutcomes(): List<String> = db.openHelper.writableDatabase.query("SELECT outcome FROM processed_source_ref ORDER BY at, rowid")
        .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    // Debit and credit

    @Test
    fun `a bank debit SMS becomes a categorised Transaction`() {
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(debitSms)))
        val t = txns().single()
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals(Paise(25_000), t.amount)
        assertEquals("1234", t.account4)
        assertEquals(Paise(1_000_000), t.balance)
        assertEquals("Swiggy", t.merchantName)
        assertEquals(TxnSource.SMS, t.source)
        assertEquals(Instant.parse("2025-01-05T08:35:00Z"), t.at)
        assertEquals(listOf("txn_created"), processedOutcomes())
    }

    @Test
    fun `a credit SMS becomes a credit Transaction`() {
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(creditSms, sentAt = t0.plusSeconds(86_400))))
        val t = txns().single()
        assertEquals(TxnType.CREDIT, t.type)
        assertEquals(Paise(100_000), t.amount)
        assertEquals("ABC123", t.reference)
    }

    @Test
    fun `the same message twice is a duplicate and changes nothing`() {
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(debitSms)))
        val before = MoneyTestDb.rowCounts(db)
        assertEquals(IngestOutcome.DUPLICATE_REF, ingest(sms(debitSms)))
        assertEquals(before, MoneyTestDb.rowCounts(db))
    }

    @Test
    fun `an SMS and an app notification for one payment make one Transaction in either order`() {
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(debitSms)))
        assertEquals(IngestOutcome.TXN_LINKED, ingest(notification(upiDebit)))
        val first = txns().single()
        assertEquals(TxnSource.MIXED, first.source)
        assertEquals("Swiggy", first.merchantName)
        assertEquals(2, first.sourceRefs.size)

        // Fresh store, opposite order.
        tearDown()
        setUp()
        assertEquals(IngestOutcome.TXN_CREATED, ingest(notification(upiDebit)))
        assertEquals(IngestOutcome.TXN_LINKED, ingest(sms(debitSms)))
        val second = txns().single()
        assertEquals(first.copy(id = second.id, sourceRefs = second.sourceRefs, category = second.category, merchantId = second.merchantId), second)
        assertEquals("Swiggy", second.merchantName)
    }

    // Requirement 3.3, 26.3, 27.3, 27.6: unlisted origins leave no trace

    @Test
    fun `an SMS from an unlisted sender is dropped and nothing at all is stored`() {
        val before = MoneyTestDb.rowCounts(db)
        assertEquals(IngestOutcome.DROPPED_UNLISTED, ingest(sms(debitSms, sender = "AX-BANKAX-S")))
        assertEquals(IngestOutcome.DROPPED_UNLISTED, ingest(sms(debitSms, sender = "FAKEBANKA")))
        assertEquals(before, MoneyTestDb.rowCounts(db))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a notification from an unlisted app, or an empty one from a listed app, is dropped`() {
        val before = MoneyTestDb.rowCounts(db)
        assertEquals(IngestOutcome.DROPPED_UNLISTED, ingest(notification(upiDebit, pkg = "com.other.app")))
        assertEquals(IngestOutcome.DROPPED_UNLISTED, ingest(notification("", title = "  ")))
        assertEquals(before, MoneyTestDb.rowCounts(db))
        assertTrue(events.isEmpty())
    }

    // Requirement 3.4, 28.3: OTP

    @Test
    fun `an OTP that also holds an amount is discarded with no trace of its content`() {
        assertEquals(IngestOutcome.OTP, ingest(sms(otpSms)))
        assertTrue(txns().isEmpty())
        assertTrue(items().isEmpty())
        assertEquals(listOf("otp"), processedOutcomes())
        val stored = MoneyTestDb.allText(db)
        assertTrue(stored.none { it.contains("482913") || it.contains("OTP") })
        // A catch-up read of the same message is recognised without parsing it again.
        assertEquals(IngestOutcome.DUPLICATE_REF, ingest(sms(otpSms)))
        assertTrue(events.none { it.detail().values.any { v -> v.contains("482913") } })
    }

    // Requirement 28.11: failed payments

    @Test
    fun `a failed payment creates nothing and changes no stored Transaction`() {
        ingest(sms(debitSms))
        val before = txns()
        assertEquals(IngestOutcome.FAILED_PAYMENT, ingest(sms(failedSms, sentAt = t0.plusSeconds(30))))
        assertEquals(before, txns())
        assertTrue(items().isEmpty())
        assertEquals(listOf("txn_created", "failed_payment"), processedOutcomes())
    }

    // Requirement 28.4, 30.1: unreadable messages

    @Test
    fun `an unreadable message goes to the Review_Queue as redacted text only`() {
        assertEquals(IngestOutcome.REVIEW_QUEUED, ingest(sms(unknownSms)))
        val item = items().single()
        assertFalse(item.redactedText.contains("9876543210"))
        assertFalse(item.redactedText.contains("bank.example"))
        assertTrue(item.redactedText.contains("[PHONE]"))
        assertTrue(item.redactedText.contains("[LINK]"))
        assertEquals("AX-BANKA-S", item.origin.value)
        assertTrue(txns().isEmpty())
        // The original text is nowhere in the store.
        assertTrue(MoneyTestDb.allText(db).none { it.contains("9876543210") || it.contains("bank.example") })
        assertEquals(IngestOutcome.DUPLICATE_REF, ingest(sms(unknownSms)))
        assertEquals(1, items().size)
    }

    @Test
    fun `an over-long message is queued without being matched`() {
        val long = "x".repeat(PARSER_LIMIT_EXCEEDED)
        assertEquals(IngestOutcome.REVIEW_QUEUED, ingest(sms(long)))
        // Nothing in it needs redacting, so the item holds it as it came.
        assertEquals(long, items().single().redactedText)
    }

    @Test
    fun `a message that cannot be redacted safely is queued with a fixed notice instead of its text`() {
        val tooLong = "x".repeat(150_000)
        assertEquals(IngestOutcome.REVIEW_QUEUED, ingest(sms(tooLong)))
        assertEquals(ReviewQueueStage.UNREADABLE_NOTICE, items().single().redactedText)
    }

    // Requirement 30.4, 32.7: tombstones are final

    @Test
    fun `the messages of a deleted Transaction create nothing when they come again`() {
        ingest(sms(debitSms))
        runBlocking { transactions.delete(txns().single().id) }
        assertEquals(IngestOutcome.TOMBSTONED, ingest(sms(debitSms)))
        assertTrue(txns().isEmpty())
        assertTrue(items().isEmpty())
    }

    @Test
    fun `a dismissed review item does not come back`() {
        ingest(sms(unknownSms))
        runBlocking { queue.dismiss(items().single().id) }
        assertEquals(IngestOutcome.TOMBSTONED, ingest(sms(unknownSms)))
        assertTrue(items().isEmpty())
    }

    // Requirement 34.2, 34.8: bills

    @Test
    fun `a bill message proposes a Bill once for the same amount and due date`() {
        assertEquals(IngestOutcome.BILL_PROPOSED, ingest(sms(billSms)))
        assertEquals(IngestOutcome.BILL_PROPOSED, ingest(sms(billSms, sentAt = t0.plusSeconds(3_600))))
        val rows = db.openHelper.writableDatabase.query("SELECT merchant_id, amount_paise, due_date, status FROM bill_proposal").use { c ->
            generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getLong(1), c.getString(2), c.getString(3)) else null }.toList()
        }
        assertEquals(listOf(listOf(null, 450_000L, "2025-01-20", "OPEN")), rows)
        assertTrue(txns().isEmpty())
        assertEquals(listOf("bill_proposed", "bill_proposed"), processedOutcomes())
    }

    // Requirement 28.5: errors do not stop the run

    @Test
    fun `an error on one message is reported by stage and the next message is processed`() {
        keys.failure = KeyError.KeystoreUnavailable
        assertEquals(IngestOutcome.ERROR, ingest(sms(debitSms)))
        val failure = events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().single()
        assertEquals(IngestStage.SOURCE_REF, failure.stage)
        assertTrue(MoneyTestDb.rowCounts(db).filterKeys { it in setOf("txn", "parsed_fact", "processed_source_ref", "review_item") }.values.all { it == 0 })

        keys.failure = null
        assertEquals(IngestOutcome.TXN_CREATED, ingest(sms(debitSms)))
    }

    // Post-commit hooks

    @Test
    fun `hooks hear about stored messages, and a failing hook changes nothing`() {
        ingest(sms(debitSms))
        hookFails = true
        assertEquals(IngestOutcome.TXN_LINKED, ingest(notification(upiDebit)))
        assertEquals(listOf(true, false), hookCalls.map { it.created })
        assertEquals("upiapp.debit.v1", hookCalls.last().formatId)
        assertEquals(IngestStage.HOOKS, events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().single().stage)
        assertEquals(1, txns().size)
        assertEquals(2, txns().single().sourceRefs.size)
    }

    // Requirement 7.1, 26.5: a batch

    @Test
    fun `a batch gives one audit entry and a count of what happened to each message`() {
        val result = runBlocking {
            pipeline.ingestBatch(
                DataSource.SMS,
                listOf(
                    sms(debitSms),
                    sms(otpSms, sentAt = t0.plusSeconds(1)),
                    sms(unknownSms, sentAt = t0.plusSeconds(2)),
                    sms(debitSms, sender = "NOTABANK", sentAt = t0.plusSeconds(3)),
                    sms(billSms, sentAt = t0.plusSeconds(4)),
                ),
            )
        }
        assertEquals(5, result.read)
        assertEquals(1, result[IngestOutcome.TXN_CREATED])
        assertEquals(1, result[IngestOutcome.OTP])
        assertEquals(1, result[IngestOutcome.REVIEW_QUEUED])
        assertEquals(1, result[IngestOutcome.DROPPED_UNLISTED])
        assertEquals(1, result[IngestOutcome.BILL_PROPOSED])
        assertEquals(listOf(IngestPipelineTest.RecordingAudit.Read(DataSource.SMS, time.now(), 5, true)), audit.reads)
        assertTrue(events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().isEmpty())
    }

    private companion object {
        /** One past the parser's longest accepted input. */
        const val PARSER_LIMIT_EXCEEDED = 2_001
    }
}
