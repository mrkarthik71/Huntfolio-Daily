package app.assistant.ingest.sms

import app.assistant.domain.model.DataSource
import app.assistant.domain.model.SenderEntry
import app.assistant.domain.port.ReaderFlags
import app.assistant.domain.port.ReaderStateStore
import app.assistant.domain.port.SenderList
import app.assistant.ingest.pipeline.BatchResult
import app.assistant.ingest.pipeline.IngestOutcome
import app.assistant.ingest.pipeline.MessageIngestor
import app.assistant.ingest.pipeline.RawMessage
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Task 21.3 for the SMS side, dependency-free: the reader-state cursor only moves forward, the import reporter starts
 * with no offer, and the core (which the receiver calls) drops an unlisted sender before any ingest or audit happens
 * (Requirements 26.3, 3.2). The receiver itself is a thin goAsync wrapper around SmsAssembler + this core; its behaviour
 * is exactly these pieces composed. The reader logic over the real pipeline is covered in SmsReaderIntegrationTest.
 */
class SmsAndroidReaderTest {

    private class FakeReaderState(initial: ReaderFlags) : ReaderStateStore {
        private val s = MutableStateFlow(initial)
        override val flags = s.asStateFlow()
        override suspend fun current() = s.value
        override suspend fun enableSms(at: Instant) = s.update { it.copy(smsEnabled = true, smsEnabledAt = at) }
        override suspend fun disableSms() = s.update { it.copy(smsEnabled = false) }
        override suspend fun advanceSmsCursor(to: Instant) = s.update { if (it.smsCursor == null || to > it.smsCursor) it.copy(smsCursor = to) else it }
        override suspend fun markHistoryImportOffered() = s.update { it.copy(historyImportOffered = true) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = s.update { it.copy(notificationsEnabled = enabled) }
    }

    private class RecordingIngestor : MessageIngestor {
        val batches = mutableListOf<Pair<DataSource, Int>>()

        override suspend fun ingestBatch(source: DataSource, messages: List<RawMessage>, readSucceeded: Boolean): BatchResult {
            batches += source to messages.size
            return BatchResult(mapOf(IngestOutcome.TXN_CREATED to messages.size))
        }
    }

    private val listedSenders = SenderList { listOf(SenderEntry("BANKA", "??-", "-?")) }

    @Test
    fun `the reader-state cursor only moves forward`() {
        val cursor = ReaderStateSmsCursor(FakeReaderState(ReaderFlags(smsEnabled = true)))
        val later = Instant.parse("2025-01-01T10:00:00Z")
        val earlier = Instant.parse("2025-01-01T09:00:00Z")
        runBlocking {
            assertNull(cursor.lastReceivedAt())
            cursor.advanceTo(later)
            assertEquals(later, cursor.lastReceivedAt())
            cursor.advanceTo(earlier)
            assertEquals(later, cursor.lastReceivedAt())
        }
    }

    @Test
    fun `declining the history import marks the offer answered so it is never offered again`() {
        val state = FakeReaderState(ReaderFlags(smsEnabled = true))
        runBlocking {
            assertFalse(state.current().historyImportOffered)
            state.markHistoryImportOffered()
            assertTrue(state.current().historyImportOffered)
        }
    }

    @Test
    fun `the core the receiver calls ingests a listed SMS and drops an unlisted one with no audit`() {
        val ingestor = RecordingIngestor()
        val core = SmsReaderCore(
            inbox = ArrayInbox(),
            senders = listedSenders,
            cursor = ReaderStateSmsCursor(FakeReaderState(ReaderFlags(smsEnabled = true))),
            ingestor = ingestor,
            time = FakeTimeSource(),
        )
        val received = Instant.parse("2025-01-05T08:36:00Z")
        runBlocking {
            val listed = checkNotNull(SmsAssembler.fromParts("AX-BANKA-S", listOf("debit of Rs 100"), null, received))
            assertEquals(1, core.onLive(listed)?.get(IngestOutcome.TXN_CREATED))

            val unlisted = checkNotNull(SmsAssembler.fromParts("MUM", listOf("hello, call 9876543210"), null, received))
            assertNull(core.onLive(unlisted))
        }
        // Exactly one ingest happened — the unlisted sender never reached the pipeline (Requirement 26.3).
        assertEquals(listOf(DataSource.SMS to 1), ingestor.batches)
    }

    private class ArrayInbox : SmsInbox {
        override suspend fun since(start: Instant, isListed: (String) -> Boolean): List<InboxSms> = emptyList()
    }
}
