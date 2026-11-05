// Feature: Phase 3 Money — Property 48: Due_Date_Rule.
package app.assistant.domain.engine.subscriptions

import app.assistant.domain.engine.bills.BillRules
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DueDateRulePropertyTest {
    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysIn(y: Int, m: Int): Int = when (m) {
        2 -> if (isLeap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** The reference: the month [months] later, on the anchor day or that month's last day. */
    private fun reference(from: LocalDate, months: Int, anchor: Int): LocalDate {
        val index = from.year * 12 + (from.monthValue - 1) + months
        val year = Math.floorDiv(index, 12)
        val month = Math.floorMod(index, 12) + 1
        return LocalDate.of(year, month, minOf(anchor, daysIn(year, month)))
    }

    private val dates = Arb.long(-20_000L..60_000L)

    @Test
    fun `Property 48 - next date follows the interval and lands on the anchor day or the month's last day`() = runTest {
        checkAll(PropertyDefaults.thorough, dates, Arb.element(SubscriptionInterval.entries), Arb.int(1..31)) { epochDay, interval, anchor ->
            val from = LocalDate.ofEpochDay(epochDay)
            val expected = when (interval) {
                SubscriptionInterval.WEEKLY -> from.plusDays(7)
                SubscriptionInterval.MONTHLY -> reference(from, 1, anchor)
                SubscriptionInterval.QUARTERLY -> reference(from, 3, anchor)
                SubscriptionInterval.YEARLY -> reference(from, 12, anchor)
            }
            assertEquals(expected, DueDateRule.next(from, interval, anchor), "from=$from interval=$interval anchor=$anchor")
        }
    }

    @Test
    fun `Property 48 - repeated application never drifts away from the anchor day`() = runTest {
        val intervals = Arb.element(SubscriptionInterval.MONTHLY, SubscriptionInterval.QUARTERLY, SubscriptionInterval.YEARLY)
        checkAll(PropertyDefaults.thorough, dates, intervals) { epochDay, interval ->
            val first = LocalDate.ofEpochDay(epochDay)
            val step = when (interval) {
                SubscriptionInterval.MONTHLY -> 1
                SubscriptionInterval.QUARTERLY -> 3
                else -> 12
            }
            var current = first
            for (k in 1..30) {
                current = DueDateRule.next(current, interval, first.dayOfMonth)
                assertEquals(reference(first, step * k, first.dayOfMonth), current, "occurrence $k from $first")
            }
        }
    }

    @Test
    fun `Bill recurrence reuses the rule and a one-off Bill has no next date`() = runTest {
        checkAll(PropertyDefaults.standard, dates, Arb.element(BillRecurrence.entries)) { epochDay, recurrence ->
            val first = LocalDate.ofEpochDay(epochDay)
            val expected = when (recurrence) {
                BillRecurrence.NONE -> first
                BillRecurrence.MONTHLY -> reference(first, 1, first.dayOfMonth)
                BillRecurrence.YEARLY -> reference(first, 12, first.dayOfMonth)
            }
            assertEquals(expected, BillRules.nextDue(first, first, recurrence))
        }
    }

    @Test
    fun `the examples from the glossary and Requirement 34`() {
        val january31 = LocalDate.of(2024, 1, 31)
        val february = DueDateRule.next(january31, SubscriptionInterval.MONTHLY, 31)
        val march = DueDateRule.next(february, SubscriptionInterval.MONTHLY, 31)
        val april = DueDateRule.next(march, SubscriptionInterval.MONTHLY, 31)
        assertEquals(listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 30)), listOf(february, march, april))
        assertEquals(LocalDate.of(2025, 2, 28), DueDateRule.next(LocalDate.of(2024, 2, 29), SubscriptionInterval.YEARLY, 29))
        assertEquals(LocalDate.of(2028, 2, 29), DueDateRule.next(LocalDate.of(2027, 2, 28), SubscriptionInterval.YEARLY, 29))
        assertEquals(LocalDate.of(2025, 2, 28), BillRules.nextDue(january31, LocalDate.of(2025, 1, 31), BillRecurrence.MONTHLY))
    }
}
