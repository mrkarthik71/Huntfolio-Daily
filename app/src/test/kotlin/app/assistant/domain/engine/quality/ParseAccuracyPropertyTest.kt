// Feature: Phase 3 Money — Property 50: Parse accuracy metric.
package app.assistant.domain.engine.quality

import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.model.SourceRef
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

private class FakeQualityStore(private val receipts: Map<SourceRef, MessageReceipt>) : QualityStore {
    val counters = HashMap<Triple<String, LocalDate, QualityMetric>, Int>()
    private val corrected = HashSet<SourceRef>()

    override suspend fun add(origin: MessageOrigin, day: LocalDate, metric: QualityMetric, count: Int) {
        counters.merge(Triple(origin.value, day, metric), count, Int::plus)
    }

    override suspend fun markCorrected(ref: SourceRef): Boolean = corrected.add(ref)

    override suspend fun receipt(ref: SourceRef): MessageReceipt? = receipts[ref]

    override fun totals(origin: MessageOrigin, from: LocalDate, to: LocalDate): Flow<QualityTotals> = flow { emit(totalsNow(origin, from, to)) }

    override suspend fun totalsNow(origin: MessageOrigin, from: LocalDate, to: LocalDate): QualityTotals {
        fun sum(metric: QualityMetric) = counters.filter { (key, _) -> key.first == origin.value && key.third == metric && key.second in from..to }.values.sum()
        return QualityTotals(sum(QualityMetric.PARSED), sum(QualityMetric.REVIEW), sum(QualityMetric.CORRECTED))
    }
}

class ParseAccuracyPropertyTest {
    private val base = LocalDate.of(2026, 11, 4)
    private val rawOrigins = listOf("AX-BANKA", "VM-bankb", " BANKC ", "com.upi.app")

    private fun normalised(origin: String) = origin.trim().lowercase()

    private class Msg(val ref: SourceRef, val origin: String, val day: LocalDate)

    private class Scenario(val messages: List<Msg>, val outcomes: Map<Int, ParseOutcomeKind>, val corrections: List<Int>, val today: LocalDate)

    private val scenarios = arbitrary {
        val messages = (0 until 12).map { i -> Msg(SourceRef("sms:v1:msg$i"), Arb.element(rawOrigins).bind(), base.minusDays(Arb.int(0..45).bind().toLong())) }
        val outcomes = HashMap<Int, ParseOutcomeKind>()
        messages.indices.forEach { i -> if (Arb.int(0..9).bind() < 8) outcomes[i] = Arb.element(ParseOutcomeKind.entries).bind() }
        val correctable = outcomes.filterValues { it == ParseOutcomeKind.DEBIT || it == ParseOutcomeKind.CREDIT }.keys.sorted()
        val corrections = ArrayList<Int>()
        if (correctable.isNotEmpty()) repeat(Arb.int(0..14).bind()) { corrections += correctable[Arb.int(0 until correctable.size).bind()] }
        Scenario(messages, outcomes, corrections.toList(), base.plusDays(Arb.int(-5..5).bind().toLong()))
    }

    private fun isParsed(kind: ParseOutcomeKind) = kind != ParseOutcomeKind.REVIEW && kind != ParseOutcomeKind.OTP

    @Test
    fun `Property 50 - counters equal the tallies, OTP is excluded, a correction counts once, and accuracy is over the last 30 days or no data`() = runTest {
        checkAll(PropertyDefaults.thorough, scenarios) { s ->
            val receipts = s.messages.associate { it.ref to MessageReceipt(MessageOrigin(it.origin), it.day) }
            val store = FakeQualityStore(receipts)
            val monitor = DefaultQualityMonitor(store, FakeTimeSource())
            s.outcomes.forEach { (i, kind) -> monitor.recordParse(MessageOrigin(s.messages[i].origin), s.messages[i].day, kind) }
            s.corrections.forEach { monitor.recordCorrection(s.messages[it].ref) }

            val counted = s.outcomes.filterValues { it != ParseOutcomeKind.OTP }
            fun total(metric: QualityMetric) = store.counters.filterKeys { it.third == metric }.values.sum()
            assertEquals(counted.values.count { isParsed(it) }, total(QualityMetric.PARSED), "parsed counter")
            assertEquals(counted.values.count { it == ParseOutcomeKind.REVIEW }, total(QualityMetric.REVIEW), "review counter")
            assertEquals(s.corrections.toSet().size, total(QualityMetric.CORRECTED), "each corrected message once")

            val windowStart = s.today.minusDays(29)
            (rawOrigins + "never-seen").forEach { raw ->
                val inWindow = s.messages.indices.filter { i ->
                    normalised(s.messages[i].origin) == normalised(raw) && s.messages[i].day in windowStart..s.today
                }
                val parsed = inWindow.count { i -> s.outcomes[i]?.let { isParsed(it) } == true }
                val review = inWindow.count { i -> s.outcomes[i] == ParseOutcomeKind.REVIEW }
                val corrected = inWindow.count { i -> i in s.corrections }
                val messagesInWindow = parsed + review
                val expected: AccuracyValue = if (messagesInWindow == 0) {
                    AccuracyValue.NoData
                } else {
                    // Tenths of a percent, rounded half up, in integers.
                    val tenths = (((parsed - corrected).toLong() * 2_000L) + messagesInWindow) / (2L * messagesInWindow)
                    AccuracyValue.Percent(BigDecimal.valueOf(tenths, 1))
                }
                assertEquals(expected, monitor.accuracy(MessageOrigin(raw), s.today).first(), "origin $raw")
            }
        }
    }

    @Test
    fun `Property 50 - the accuracy formula is null without messages and otherwise parsed minus corrected over parsed plus review`() = runTest {
        checkAll(PropertyDefaults.thorough, Arb.int(0..500), Arb.int(0..500), Arb.int(0..500)) { parsed, correctedRaw, review ->
            val corrected = if (parsed == 0) 0 else correctedRaw % (parsed + 1)
            val value = QualityMath.accuracy(parsed, corrected, review)
            if (parsed + review == 0) {
                assertNull(value)
            } else {
                val n = (parsed + review).toLong()
                val tenths = ((parsed - corrected).toLong() * 2_000L + n) / (2L * n)
                assertEquals(BigDecimal.valueOf(tenths, 1), value)
            }
        }
    }

    @Test
    fun `the window is today and the 29 days before`() {
        assertEquals(LocalDate.of(2026, 10, 6), QualityMath.windowStart(LocalDate.of(2026, 11, 4)))
    }
}
