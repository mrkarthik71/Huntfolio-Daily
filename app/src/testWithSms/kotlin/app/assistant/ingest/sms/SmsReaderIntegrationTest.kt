package app.assistant.ingest.sms

import android.app.Application
import app.assistant.data.db.DbProvider
import app.assistant.data.repo.RoomSenderList
import app.assistant.domain.model.DataSource
import app.assistant.ingest.pipeline.IngestOutcome
import app.assistant.ingest.pipeline.MessageGen
import app.assistant.ingest.pipeline.PipelineFixture
import app.assistant.security.log.DiagnosticEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** The Sms_Reader logic over the real pipeline and stores, with a fake phone inbox (task 21.1, without the Android parts). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsReaderIntegrationTest {
    private lateinit var fx: PipelineFixture
    private lateinit var core: SmsReaderCore
    private val inbox = ArrayInbox()
    private val cursor = MemoryCursor()
    private val enabledAt: Instant = Instant.parse("2025-01-01T00:00:00Z")

    private class ArrayInbox(var rows: List<InboxSms> = emptyList()) : SmsInbox {
        val asked = mutableListOf<String>()

        override suspend fun since(start: Instant, isListed: (String) -> Boolean): List<InboxSms> =
            rows.filter { it.dateReceived >= start }.filter { row -> row.address != null && isListed(row.address).also { if (!it) asked += "unlisted" } }
    }

    private class MemoryCursor : SmsCursor {
        var last: Instant? = null

        override suspend fun lastReceivedAt() = last

        override suspend fun advanceTo(at: Instant) {
            last = at
        }
    }

    @Before
    fun setUp() {
        fx = PipelineFixture()
        core = SmsReaderCore(inbox, RoomSenderList(DbProvider { fx.db }), cursor, fx.pipeline, fx.time)
    }

    @After
    fun tearDown() = fx.close()

    private val pay = MessageGen.Pay(25_000, 5, 14, 5, "Swiggy", "1234", "REF123456", 1_000_000)
    private val debitBody = MessageGen.debitSms(pay)
    private val otpBody = MessageGen.otpSms("482913", pay)
    private val unknownBody = "Dear Asha Menon your statement is ready. Call 9876543210 today"
    private val sentDebit: Instant = Instant.parse("2025-01-05T08:35:58Z")
    private val receivedDebit: Instant = Instant.parse("2025-01-05T08:36:00Z")

    private fun row(address: String, body: String, received: Instant) = InboxSms(address, body, received.minusSeconds(2), received)

    @Test
    fun `catch-up reads bank SMS from the inbox through the real pipeline and leaves everything else alone`() {
        inbox.rows = listOf(
            InboxSms("AX-BANKA-S", debitBody, sentDebit, receivedDebit),
            row("AX-BANKA-S", otpBody, receivedDebit.plusSeconds(60)),
            row("AX-BANKA-S", unknownBody, receivedDebit.plusSeconds(120)),
            row("MUM", "Are you coming tonight? Call 9876543210", receivedDebit.plusSeconds(30)),
        )

        val report = runBlocking { core.catchUp(enabledAt) }

        assertEquals(ReadStatus.READ, report.status)
        assertEquals(3, report.batch.read)
        assertEquals(1, report.batch[IngestOutcome.TXN_CREATED])
        assertEquals(1, report.batch[IngestOutcome.OTP])
        assertEquals(1, report.batch[IngestOutcome.REVIEW_QUEUED])
        assertEquals(1, fx.transactionList().size)
        assertEquals(1, fx.reviewItems().size)
        // The personal SMS went nowhere: not stored, not counted, not logged.
        assertTrue(fx.events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().isEmpty())
        assertTrue(app.assistant.data.repo.MoneyTestDb.allText(fx.db).none { it.contains("coming tonight") })
        // One Audit_Log entry for the read, with the number of messages read.
        assertEquals(listOf(DataSource.SMS to 3), fx.audit.reads.map { it.source to it.items })
        assertEquals(receivedDebit.plusSeconds(120), cursor.last)
    }

    @Test
    fun `reading again re-reads the last message harmlessly and finds only what is new`() {
        inbox.rows = listOf(InboxSms("AX-BANKA-S", debitBody, sentDebit, receivedDebit))
        runBlocking { core.catchUp(enabledAt) }
        val afterFirst = fx.snapshot()

        // Nothing new: the cursor sits on the last message, which is read again and recognised.
        val again = runBlocking { core.catchUp(enabledAt) }
        assertEquals(1, again.batch[IngestOutcome.DUPLICATE_REF])
        assertEquals(afterFirst, fx.snapshot())

        // A message that arrived while the app was stopped is picked up.
        val later = MessageGen.Pay(99_900, 6, 9, 0, "Zomato", "1234", "REF654321", 900_000)
        inbox.rows = inbox.rows + row("VM-BANKA", MessageGen.debitSms(later), receivedDebit.plusSeconds(3_600))
        val third = runBlocking { core.catchUp(enabledAt) }
        assertEquals(1, third.batch[IngestOutcome.TXN_CREATED])
        assertEquals(2, fx.transactionList().size)
    }

    @Test
    fun `an SMS read live and then again from the inbox is the same message`() {
        val live = checkNotNull(SmsAssembler.fromParts("AX-BANKA-S", listOf(debitBody.take(40), debitBody.drop(40)), sentDebit, receivedDebit))
        val batch = runBlocking { core.onLive(live) }
        assertEquals(1, batch?.get(IngestOutcome.TXN_CREATED))

        inbox.rows = listOf(InboxSms("AX-BANKA-S", debitBody, sentDebit, receivedDebit))
        val report = runBlocking { core.catchUp(enabledAt = Instant.parse("2025-01-01T00:00:00Z")) }
        assertEquals(1, report.batch[IngestOutcome.DUPLICATE_REF])
        assertEquals(1, fx.transactionList().size)
    }

    @Test
    fun `a live SMS from an unlisted sender leaves no trace at all`() {
        val counts = app.assistant.data.repo.MoneyTestDb.rowCounts(fx.db)
        assertNull(runBlocking { core.onLive(checkNotNull(SmsAssembler.fromParts("MUM", listOf("hello there, call 9876543210"), null, receivedDebit))) })
        assertEquals(counts, app.assistant.data.repo.MoneyTestDb.rowCounts(fx.db))
        assertTrue(fx.audit.reads.isEmpty())
        assertTrue(fx.events.isEmpty())
    }

    @Test
    fun `the history import reports what it did, and a payment seen by SMS and read again by import is not counted twice`() {
        val enabledOn = LocalDate.of(2025, 2, 10)
        val unclear = row("AX-BANKA-S", unknownBody, receivedDebit.plusSeconds(120))
        inbox.rows = listOf(
            InboxSms("AX-BANKA-S", debitBody, sentDebit, receivedDebit),
            row("AX-BANKA-S", otpBody, receivedDebit.plusSeconds(60)),
            unclear,
            row("AX-BANKA-S", "Old news from long ago", Instant.parse("2024-06-01T00:00:00Z")),
        )
        // The debit was already read live.
        runBlocking { core.onLive(checkNotNull(SmsAssembler.fromInbox(inbox.rows.first()))) }

        val summary = runBlocking { core.historyImport(enabledOn) }

        assertEquals(ImportSummary(ReadStatus.READ, smsRead = 3, transactionsCreated = 0, reviewItemsAdded = 1), summary)
        assertEquals(1, fx.transactionList().size)
        assertNotNull(fx.reviewItems().singleOrNull())
    }
}
