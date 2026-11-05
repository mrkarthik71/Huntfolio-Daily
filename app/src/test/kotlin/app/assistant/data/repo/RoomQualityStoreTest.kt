package app.assistant.data.repo

import android.app.Application
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.ParsedFactEntity
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.quality.AccuracyValue
import app.assistant.domain.engine.quality.DefaultQualityMonitor
import app.assistant.domain.engine.quality.ParseOutcomeKind
import app.assistant.domain.model.SourceRef
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** The Quality_Monitor over the real `quality_counter` table (Requirements 36.1, 36.2, 36.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomQualityStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomQualityStore
    private lateinit var monitor: DefaultQualityMonitor
    private val time = FakeTimeSource(Instant.parse("2026-11-04T06:00:00Z"))
    private val today = LocalDate.of(2026, 11, 4)
    private val origin = MessageOrigin("AX-BANKA")

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        store = RoomQualityStore(DbProvider { db }, time)
        monitor = DefaultQualityMonitor(store, time)
    }

    @After
    fun tearDown() = db.close()

    private fun storeFact(ref: String, receivedAt: Instant) {
        runBlocking {
            db.transactionDao().insertFact(
                ParsedFactEntity(
                    sourceRef = ref,
                    origin = "AX-BANKA",
                    sourceKind = "SMS",
                    type = "DEBIT",
                    amountPaise = 10_000,
                    at = receivedAt.toEpochMilli(),
                    receivedAt = receivedAt.toEpochMilli(),
                    reference = null,
                    account4 = null,
                    merchantOrVpa = null,
                    balancePaise = null,
                    isReversal = false,
                    formatId = "test",
                    transactionId = null,
                ),
            )
        }
    }

    @Test
    fun `counters add up per origin and day and a correction counts once on the day the message arrived`() {
        runBlocking {
            val received = Instant.parse("2026-10-10T06:00:00Z")
            storeFact("sms:v1:one", received)
            monitor.recordParse(origin, LocalDate.of(2026, 10, 10), ParseOutcomeKind.DEBIT)
            monitor.recordParse(origin, LocalDate.of(2026, 10, 10), ParseOutcomeKind.CREDIT)
            monitor.recordParse(origin, today, ParseOutcomeKind.REVIEW)
            monitor.recordParse(origin, today, ParseOutcomeKind.OTP)
            monitor.recordCorrection(SourceRef("sms:v1:one"))
            monitor.recordCorrection(SourceRef("sms:v1:one"))
            monitor.recordCorrection(SourceRef("sms:v1:unknown"))

            val totals = store.totalsNow(MessageOrigin("ax-banka"), LocalDate.of(2026, 10, 6), today)
            assertEquals(2, totals.parsed)
            assertEquals(1, totals.review)
            assertEquals(1, totals.corrected)
            // (2 - 1) / (2 + 1) = 33.3 percent; the OTP is not counted.
            assertEquals(AccuracyValue.Percent(BigDecimal("33.3")), monitor.accuracy(origin, today).first())
            // Forty days later every message is outside the 30 days.
            assertEquals(AccuracyValue.NoData, monitor.accuracy(origin, today.plusDays(40)).first())
            assertEquals(AccuracyValue.NoData, monitor.accuracy(MessageOrigin("OTHER"), today).first())
        }
    }

    @Test
    fun `the stored rows hold counts and Source_Refs only`() {
        runBlocking {
            storeFact("sms:v1:two", Instant.parse("2026-11-01T06:00:00Z"))
            monitor.recordParse(origin, LocalDate.of(2026, 11, 1), ParseOutcomeKind.DEBIT)
            monitor.recordCorrection(SourceRef("sms:v1:two"))
            assertTrue(store.markCorrected(SourceRef("sms:v1:fresh")))
            assertFalse(store.markCorrected(SourceRef("sms:v1:fresh")))
            val values = MoneyTestDb.allText(db, skip = emptySet()).filter { it.startsWith("sms:v1:") }
            assertTrue(values.all { it == "sms:v1:two" || it == "sms:v1:fresh" })
        }
    }
}
