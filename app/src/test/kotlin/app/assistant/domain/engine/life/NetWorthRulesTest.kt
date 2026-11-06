package app.assistant.domain.engine.life

import app.assistant.domain.model.Holding
import app.assistant.domain.model.HoldingKind
import app.assistant.domain.model.Paise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NetWorthRulesTest {
    private val today = LocalDate.of(2026, 11, 18)
    private fun h(id: Long, kind: HoldingKind, value: Long, monthly: Long = 0, day: Int? = null) = Holding(id, "h$id", kind, Paise(value), Paise(monthly), day, today)

    @Test
    fun `net worth subtracts debts and sums monthly outflow`() {
        val s = NetWorthRules.summary(listOf(h(1, HoldingKind.BANK, 5_000_000), h(2, HoldingKind.MUTUAL_FUND, 20_000_000, 500_000, 5), h(3, HoldingKind.LOAN, 30_000_000, 1_200_000, 10)))
        assertEquals(Paise(25_000_000), s.assets)
        assertEquals(Paise(30_000_000), s.liabilities)
        assertEquals(-5_000_000L, s.netPaise)
        assertEquals(Paise(1_700_000), s.monthlyOut)
    }

    @Test
    fun `due reminders fire the day before and on the day`() {
        assertEquals(LocalDate.of(2026, 12, 5), NetWorthRules.nextDue(5, today))
        assertEquals(LocalDate.of(2026, 11, 18), NetWorthRules.nextDue(18, today))
        val sip = h(2, HoldingKind.MUTUAL_FUND, 1, 500_000, 19)
        val alerts = NetWorthRules.dueAlerts(listOf(sip), today, today.plusDays(1))
        assertEquals(setOf("due:2:2026-11-19:1", "due:2:2026-11-19:0"), alerts.map { it.refKey }.toSet())
        assertEquals(emptyList<MoneyDue>(), NetWorthRules.dueAlerts(listOf(sip.copy(monthly = Paise(0))), today, today.plusDays(1)))
    }
}
