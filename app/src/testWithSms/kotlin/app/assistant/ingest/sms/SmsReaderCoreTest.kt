package app.assistant.ingest.sms

import app.assistant.domain.model.DataSource
import app.assistant.domain.model.SenderEntry
import app.assistant.domain.port.SenderList
import app.assistant.ingest.pipeline.BatchResult
import app.assistant.ingest.pipeline.IngestOutcome
import app.assistant.ingest.pipeline.MessageIngestor
import app.assistant.ingest.pipeline.RawMessage
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

class SmsReaderCoreTest {
    private val time = FakeTimeSource()
    private val senders = SenderList { listOf(SenderEntry("BANKA", "??-", "-?")) }
    private val enabledAt: Instant = Instant.parse("2026-11-01T00:00:00Z")

    private fun at(seconds: Long): Instant = enabledAt.plusSeconds(seconds)

    private fun row(address: String?, body: String?, receivedAfter: Long) = InboxSms(address, body, at(receivedAfter - 2), at(receivedAfter))

    private class FakeInbox(var rows: List<InboxSms>, var failure: Exception? = null) : SmsInbox {
        val starts = mutableListOf<Instant>()
        val tested = mutableListOf<String>()

        override suspend fun since(start: Instant, isListed: (String) -> Boolean): List<InboxSms> {
            starts += start
            failure?.let { throw it }
            return rows.filter { it.dateReceived >= start }.filter { row ->
                row.address?.let {
                    tested += it
                    isListed(it)
                } == true
            }
        }
    }

    private class FakeCursor(var last: Instant? = null) : SmsCursor {
        override suspend fun lastReceivedAt() = last

        override suspend fun advanceTo(at: Instant) {
            val previous = last
            check(previous == null || at > previous) { "the cursor must only move forward" }
            last = at
        }
    }

    private class FakeIngestor(val outcome: (RawMessage) -> IngestOutcome = { IngestOutcome.TXN_CREATED }) : MessageIngestor {
        data class Call(val source: DataSource, val messages: List<RawMessage>, val readSucceeded: Boolean)

        val calls = mutableListOf<Call>()

        override suspend fun ingestBatch(source: DataSource, messages: List<RawMessage>, readSucceeded: Boolean): BatchResult {
            calls += Call(source, messages, readSucceeded)
            return BatchResult(messages.groupingBy(outcome).eachCount())
        }
    }

    private fun core(inbox: FakeInbox, cursor: FakeCursor, ingestor: FakeIngestor) = SmsReaderCore(inbox, senders, cursor, ingestor, time)

    // Catch-up (Requirement 26.6)

    @Test
    fun `catch-up reads from the last processed SMS, oldest first, and moves the cursor to the newest`() = runBlocking {
        val inbox = FakeInbox(listOf(row("AX-BANKA-S", "c", 300), row("AX-BANKA-S", "a", 100), row("AX-BANKA-S", "b", 200)))
        val cursor = FakeCursor(last = at(100))
        val ingestor = FakeIngestor()

        val report = core(inbox, cursor, ingestor).catchUp(enabledAt)

        assertEquals(listOf(at(100)), inbox.starts)
        assertEquals(ReadStatus.READ, report.status)
        assertEquals(listOf("a", "b", "c"), ingestor.calls.single().messages.map { (it as RawMessage.Sms).body })
        assertEquals(at(300), cursor.last)
    }

    @Test
    fun `with no cursor yet, catch-up starts when reading was switched on`() = runBlocking {
        val inbox = FakeInbox(listOf(row("AX-BANKA-S", "old", -500), row("AX-BANKA-S", "new", 50)))
        val ingestor = FakeIngestor()
        core(inbox, FakeCursor(), ingestor).catchUp(enabledAt)
        assertEquals(listOf(enabledAt), inbox.starts)
        assertEquals(listOf("new"), ingestor.calls.single().messages.map { (it as RawMessage.Sms).body })
    }

    @Test
    fun `with reading not enabled catch-up touches nothing, not even the audit log`() = runBlocking {
        val inbox = FakeInbox(listOf(row("AX-BANKA-S", "x", 10)))
        val ingestor = FakeIngestor()
        val report = core(inbox, FakeCursor(last = at(0)), ingestor).catchUp(enabledAt = null)
        assertEquals(ReadStatus.NOTHING_TO_READ, report.status)
        assertTrue(inbox.starts.isEmpty())
        assertTrue(ingestor.calls.isEmpty())
    }

    @Test
    fun `an empty inbox is still a read the audit log records`() = runBlocking {
        val ingestor = FakeIngestor()
        val cursor = FakeCursor(last = at(10))
        val report = core(FakeInbox(emptyList()), cursor, ingestor).catchUp(enabledAt)
        assertEquals(ReadStatus.NOTHING_TO_READ, report.status)
        assertEquals(listOf(FakeIngestor.Call(DataSource.SMS, emptyList(), true)), ingestor.calls)
        assertEquals(at(10), cursor.last)
    }

    @Test
    fun `only listed senders are asked for and read, and an SMS from anyone else is never seen by the pipeline`() = runBlocking {
        val inbox =
            FakeInbox(listOf(row("AX-BANKA-S", "bank", 10), row("MUM", "personal message", 20), row("FAKEBANKA", "look-alike", 30), row(null, "no sender", 40)))
        val ingestor = FakeIngestor()
        core(inbox, FakeCursor(), ingestor).catchUp(enabledAt)
        assertEquals(listOf("bank"), ingestor.calls.single().messages.map { (it as RawMessage.Sms).body })
    }

    @Test
    fun `a batch with an error leaves the cursor where it was so the message is read again`() = runBlocking {
        val inbox = FakeInbox(listOf(row("AX-BANKA-S", "ok", 10), row("AX-BANKA-S", "bad", 20)))
        val cursor = FakeCursor(last = at(0))
        val ingestor = FakeIngestor { if ((it as RawMessage.Sms).body == "bad") IngestOutcome.ERROR else IngestOutcome.TXN_CREATED }
        core(inbox, cursor, ingestor).catchUp(enabledAt)
        assertEquals(at(0), cursor.last)
    }

    @Test
    fun `the cursor never moves backwards`() = runBlocking {
        val inbox = FakeInbox(listOf(row("AX-BANKA-S", "a", 100)))
        val cursor = FakeCursor(last = at(500))
        // Catch-up starts at 500, so the 100 row is not even read; and a live message older than the cursor leaves it alone.
        core(inbox, cursor, FakeIngestor()).catchUp(enabledAt)
        core(inbox, cursor, FakeIngestor()).onLive(RawMessage.Sms("AX-BANKA-S", "old", at(98), at(100)))
        assertEquals(at(500), cursor.last)
    }

    // A failed read (Requirement 7.1)

    @Test
    fun `an inbox that cannot be read is recorded as a failed read and changes nothing else`() = runBlocking {
        val inbox = FakeInbox(emptyList(), failure = SecurityException("permission withdrawn"))
        val cursor = FakeCursor(last = at(5))
        val ingestor = FakeIngestor()
        val report = core(inbox, cursor, ingestor).catchUp(enabledAt)
        assertEquals(ReadStatus.FAILED, report.status)
        assertEquals(listOf(FakeIngestor.Call(DataSource.SMS, emptyList(), false)), ingestor.calls)
        assertEquals(at(5), cursor.last)
    }

    @Test
    fun `cancellation is not a failed read`() {
        val inbox = FakeInbox(emptyList(), failure = CancellationException("stop"))
        val ingestor = FakeIngestor()
        assertThrows(CancellationException::class.java) { runBlocking { core(inbox, FakeCursor(), ingestor).catchUp(enabledAt) } }
        assertTrue(ingestor.calls.isEmpty())
    }

    // The history import (Requirements 26.2, 26.5)

    @Test
    fun `the import reads three months back and reports what it did`() = runBlocking {
        val enabledOn = LocalDate.of(2026, 11, 4)
        val start = SmsReaderRules.historyImportStart(enabledOn, time.zone())
        val rows = listOf(
            InboxSms("AX-BANKA-S", "before", start.minusSeconds(10), start.minusSeconds(5)),
            InboxSms("AX-BANKA-S", "txn1", start, start.plusSeconds(10)),
            InboxSms("AX-BANKA-S", "txn2", start, start.plusSeconds(20)),
            InboxSms("AX-BANKA-S", "unclear", start, start.plusSeconds(30)),
            InboxSms("AX-BANKA-S", "code", start, start.plusSeconds(40)),
        )
        val ingestor = FakeIngestor {
            when ((it as RawMessage.Sms).body) {
                "unclear" -> IngestOutcome.REVIEW_QUEUED
                "code" -> IngestOutcome.OTP
                else -> IngestOutcome.TXN_CREATED
            }
        }
        val cursor = FakeCursor()
        val summary = core(FakeInbox(rows), cursor, ingestor).historyImport(enabledOn)

        assertEquals(ImportSummary(ReadStatus.READ, smsRead = 4, transactionsCreated = 2, reviewItemsAdded = 1), summary)
        assertEquals(start.plusSeconds(40), cursor.last)
    }

    @Test
    fun `an import with nothing to read, or one that fails, says so`() = runBlocking {
        val empty = core(FakeInbox(emptyList()), FakeCursor(), FakeIngestor()).historyImport(LocalDate.of(2026, 11, 4))
        assertEquals(ImportSummary(ReadStatus.NOTHING_TO_READ, 0, 0, 0), empty)
        val failed = core(
            FakeInbox(emptyList(), failure = IllegalStateException("inbox unavailable")),
            FakeCursor(),
            FakeIngestor(),
        ).historyImport(LocalDate.of(2026, 11, 4))
        assertEquals(ImportSummary(ReadStatus.FAILED, 0, 0, 0), failed)
    }

    // A live SMS (Requirements 26.1, 26.3)

    @Test
    fun `a live SMS from a listed sender is ingested as a one-message read and moves the cursor`() = runBlocking {
        val cursor = FakeCursor(last = at(10))
        val ingestor = FakeIngestor()
        val message = RawMessage.Sms("VM-BANKA", "text", at(98), at(100))
        val batch = core(FakeInbox(emptyList()), cursor, ingestor).onLive(message)
        assertEquals(1, batch?.read)
        assertEquals(listOf(FakeIngestor.Call(DataSource.SMS, listOf(message), true)), ingestor.calls)
        assertEquals(at(100), cursor.last)
    }

    @Test
    fun `a live SMS from anyone else is dropped before the pipeline, with no audit entry and no cursor change`() = runBlocking {
        val cursor = FakeCursor(last = at(10))
        val ingestor = FakeIngestor()
        val c = core(FakeInbox(emptyList()), cursor, ingestor)
        assertNull(c.onLive(RawMessage.Sms("MUM", "see you at 6", at(98), at(100))))
        assertNull(c.onLive(RawMessage.Sms("FAKEBANKA", "look-alike", at(98), at(100))))
        assertTrue(ingestor.calls.isEmpty())
        assertEquals(at(10), cursor.last)
    }

    @Test
    fun `a live SMS that errors leaves the cursor so catch-up finds it`() = runBlocking {
        val cursor = FakeCursor(last = at(10))
        core(FakeInbox(emptyList()), cursor, FakeIngestor { IngestOutcome.ERROR }).onLive(RawMessage.Sms("BANKA", "x", at(98), at(100)))
        assertEquals(at(10), cursor.last)
    }
}
