package app.assistant.ingest.sms

import app.assistant.ingest.notifications.ReaderState
import app.assistant.ingest.pipeline.BatchResult
import app.assistant.ingest.pipeline.IngestOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SmsReaderRulesTest {
    private val t1: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val t2: Instant = Instant.parse("2026-11-03T10:00:00Z")

    // Requirement 26.6

    @Test
    fun `catch-up starts at the last processed SMS, but never before reading was switched on`() {
        assertEquals(t2, SmsReaderRules.catchUpSince(lastReceivedAt = t2, enabledAt = t1))
        assertEquals(t2, SmsReaderRules.catchUpSince(lastReceivedAt = t1, enabledAt = t2))
        assertEquals(t1, SmsReaderRules.catchUpSince(lastReceivedAt = null, enabledAt = t1))
    }

    @Test
    fun `with reading not enabled there is nothing to catch up`() {
        assertNull(SmsReaderRules.catchUpSince(lastReceivedAt = t1, enabledAt = null))
        assertNull(SmsReaderRules.catchUpSince(lastReceivedAt = null, enabledAt = null))
    }

    // Requirement 26.2

    @Test
    fun `the import covers three calendar months back from the start of that day in the device zone`() {
        val ist = ZoneId.of("Asia/Kolkata")
        assertEquals(Instant.parse("2026-08-03T18:30:00Z"), SmsReaderRules.historyImportStart(LocalDate.of(2026, 11, 4), ist))
        // A short month: the last day of it.
        assertEquals(Instant.parse("2026-02-27T18:30:00Z"), SmsReaderRules.historyImportStart(LocalDate.of(2026, 5, 31), ist))
        assertEquals(Instant.parse("2024-02-28T18:30:00Z"), SmsReaderRules.historyImportStart(LocalDate.of(2024, 5, 31), ist))
        assertEquals(Instant.parse("2024-02-29T00:00:00Z"), SmsReaderRules.historyImportStart(LocalDate.of(2024, 5, 29), ZoneId.of("UTC")))
    }

    @Test
    fun `the import is offered once, only when reading is on and the permission is granted`() {
        assertTrue(SmsReaderRules.shouldOfferHistoryImport(alreadyOffered = false, enabled = true, permissionGranted = true))
        assertFalse(SmsReaderRules.shouldOfferHistoryImport(alreadyOffered = true, enabled = true, permissionGranted = true))
        assertFalse(SmsReaderRules.shouldOfferHistoryImport(alreadyOffered = false, enabled = false, permissionGranted = true))
        assertFalse(SmsReaderRules.shouldOfferHistoryImport(alreadyOffered = false, enabled = true, permissionGranted = false))
    }

    @Test
    fun `the reader state follows the switch and the permission`() {
        assertEquals(ReaderState.OFF, SmsReaderRules.state(enabled = false, permissionGranted = true))
        assertEquals(ReaderState.OFF, SmsReaderRules.state(enabled = false, permissionGranted = false))
        assertEquals(ReaderState.NEEDS_PERMISSION, SmsReaderRules.state(enabled = true, permissionGranted = false))
        assertEquals(ReaderState.ACTIVE, SmsReaderRules.state(enabled = true, permissionGranted = true))
    }

    // Requirement 26.5

    @Test
    fun `the import summary counts SMS read, Transactions created and review items added`() {
        val batch = BatchResult(
            mapOf(
                IngestOutcome.TXN_CREATED to 4,
                IngestOutcome.TXN_LINKED to 2,
                IngestOutcome.REVIEW_QUEUED to 3,
                IngestOutcome.OTP to 5,
                IngestOutcome.DUPLICATE_REF to 1,
            ),
        )
        assertEquals(ImportSummary(ReadStatus.READ, smsRead = 15, transactionsCreated = 4, reviewItemsAdded = 3), ImportSummary.of(ReadStatus.READ, batch))
        assertEquals(ImportSummary(ReadStatus.FAILED, 0, 0, 0), ImportSummary.of(ReadStatus.FAILED, BatchResult(emptyMap())))
    }
}
