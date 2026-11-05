// Feature: Phase 3 Money — Property 49: Spending report arithmetic.
package app.assistant.domain.engine.reports

import app.assistant.domain.engine.review.ReportCalculator
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.SignedPaise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class SpendingReportsPropertyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val month = YearMonth.of(2026, 10)
    private val week = PeriodInstance(GoalPeriod.WEEK, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
    private val start = LocalDate.of(2026, 9, 5).atStartOfDay(zone).toInstant()

    private val categoryNames = listOf("", "Zeta", "Alpha", "Mid", "Beta", "Gamma", "Omega", "Delta", "Kappa")
    private val names = CategoryNames { categoryNames[it.value.toInt()] }

    private fun txn(id: Long, type: TxnType, amount: Long, at: Instant, category: Long, reversal: Boolean) = Transaction(
        id = TransactionId(id),
        type = type,
        amount = Paise(amount),
        at = at,
        merchantId = MerchantId(1),
        merchantName = "Shop",
        category = CategoryId(category),
        source = TxnSource.SMS,
        reference = null,
        account4 = null,
        balance = null,
        isReversal = reversal,
        editedFields = emptySet(),
        categoryUserSet = false,
        sourceRefs = emptyList(),
    )

    /** Few distinct amounts, so category totals tie often and the name order is exercised. */
    private val transactions = arbitrary {
        val out = ArrayList<Transaction>()
        repeat(Arb.int(0..40).bind()) { i ->
            val type = Arb.element(TxnType.DEBIT, TxnType.CREDIT).bind()
            out += txn(
                id = i + 1L,
                type = type,
                amount = Arb.element(100L, 200L, 300L, 500L).bind(),
                at = start.plusSeconds(Arb.long(0L..(70L * 86_400L)).bind()),
                category = Arb.long(1L..8L).bind(),
                reversal = type == TxnType.CREDIT && Arb.boolean().bind(),
            )
        }
        out.toList()
    }

    private fun dateOf(t: Transaction): LocalDate = t.at.atZone(zone).toLocalDate()

    private fun spend(txns: List<Transaction>, inPeriod: (LocalDate) -> Boolean): Map<Long, Long> =
        txns.filter { it.type == TxnType.DEBIT && !it.isReversal && inPeriod(dateOf(it)) }
            .groupBy { it.category.value }
            .mapValues { (_, list) -> list.sumOf { it.amount.value } }

    private fun inMonth(m: YearMonth): (LocalDate) -> Boolean = { it.year == m.year && it.monthValue == m.monthValue }

    private fun inWeek(): (LocalDate) -> Boolean = { !it.isBefore(week.first) && !it.isAfter(week.last) }

    private fun reversals(txns: List<Transaction>, inPeriod: (LocalDate) -> Boolean): Long =
        txns.filter { it.isReversal && inPeriod(dateOf(it)) }.sumOf { it.amount.value }

    /** Half away from zero, in integers. */
    private fun roundedPercent(change: Long, previous: Long): Long {
        val magnitude = (2 * Math.abs(change) * 100 + previous) / (2 * previous)
        return if (change < 0) -magnitude else magnitude
    }

    @Test
    fun `Property 49 - monthly total, per-category amounts, changes and the separate Reversal line`() = runTest {
        checkAll(PropertyDefaults.thorough, transactions) { txns ->
            val report = ReportCalculator.monthlySpending(month, txns, zone, names)
            val current = spend(txns, inMonth(month))
            val before = spend(txns, inMonth(month.minusMonths(1)))
            assertEquals(month, report.month)
            assertEquals(current.values.sum(), report.total.value, "total spend")
            assertEquals(report.total.value, report.lines.sumOf { it.amount.value }, "per-category amounts add up to the total")
            assertEquals((current.keys + before.keys).sorted(), report.lines.map { it.category.value }.sorted(), "every category with spend this or last month")
            report.lines.forEach { line ->
                val amount = current[line.category.value] ?: 0L
                val previous = before[line.category.value] ?: 0L
                assertEquals(amount, line.amount.value)
                assertEquals(previous, line.previous.value)
                assertEquals(SignedPaise(amount - previous), line.change)
                if (previous > 0) assertEquals(roundedPercent(amount - previous, previous), line.percent) else assertNull(line.percent)
                assertEquals(categoryNames[line.category.value.toInt()], line.name)
            }
            assertEquals(reversals(txns, inMonth(month)), report.reversals.value, "Reversals are a separate line")
        }
    }

    @Test
    fun `Property 49 - weekly total, the 5 highest categories in order, and Other`() = runTest {
        checkAll(PropertyDefaults.thorough, transactions) { txns ->
            val report = ReportCalculator.weeklySpending(week, txns, zone, names)
            val byCategory = spend(txns, inWeek())
            val total = byCategory.values.sum()
            assertEquals(total, report.total.value, "total spend")
            val expectedOrder = byCategory.entries
                .sortedWith(compareBy<Map.Entry<Long, Long>>({ -it.value }, { categoryNames[it.key.toInt()] }))
            assertEquals(expectedOrder.take(5).map { it.key }, report.top.map { it.category.value }, "top 5 by amount, ties by name")
            assertTrue(report.top.size <= 5)
            assertEquals(expectedOrder.drop(5).sumOf { it.value }, report.other.value, "Other")
            assertEquals(total, report.top.sumOf { it.amount.value } + report.other.value, "the amounts shown add up to the total")
            assertEquals(reversals(txns, inWeek()), report.reversals.value, "Reversals are a separate line")
        }
    }

    @Test
    fun `an empty period has total zero and no lines`() {
        val report = ReportCalculator.monthlySpending(month, emptyList(), zone, names)
        assertEquals(0L, report.total.value)
        assertTrue(report.lines.isEmpty())
        val weekly = ReportCalculator.weeklySpending(week, emptyList(), zone, names)
        assertEquals(0L, weekly.total.value + weekly.other.value)
        assertTrue(weekly.top.isEmpty())
    }
}
