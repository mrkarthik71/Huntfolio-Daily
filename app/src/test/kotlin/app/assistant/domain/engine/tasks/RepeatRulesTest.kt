package app.assistant.domain.engine.tasks

import app.assistant.domain.model.Paise
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RepeatRulesTest {
    private val fri = LocalDate.of(2026, 11, 13)

    @Test
    fun nextDates() {
        assertEquals(fri.plusDays(1), RepeatRules.next(TaskRepeat(RepeatRule.DAILY), fri, fri))
        assertEquals(LocalDate.of(2026, 11, 16), RepeatRules.next(TaskRepeat(RepeatRule.WEEKDAYS), fri, fri))
        assertEquals(fri.plusWeeks(1), RepeatRules.next(TaskRepeat(RepeatRule.WEEKLY), fri, fri))
        assertEquals(fri.plusDays(3), RepeatRules.next(TaskRepeat(RepeatRule.EVERY_N_DAYS, 3), fri, fri))
    }

    @Test
    fun monthlyKeepsItsDayAndSkipsPastWhenDoneLate() {
        val planned = LocalDate.of(2026, 9, 5)
        assertEquals(LocalDate.of(2026, 12, 5), RepeatRules.next(TaskRepeat(RepeatRule.MONTHLY), planned, fri))
        assertEquals(LocalDate.of(2026, 12, 5), RepeatRules.next(TaskRepeat(RepeatRule.MONTHLY), LocalDate.of(2026, 11, 5), fri))
    }

    @Test
    fun iouBalancesNetPerPerson() {
        val ious = listOf(
            Iou(1, "Ravi", Paise(50_000), true, "dinner", fri, null),
            Iou(2, "ravi ", Paise(20_000), false, "auto", fri, null),
            Iou(3, "Priya", Paise(10_000), false, "tickets", fri, null),
            Iou(4, "Ravi", Paise(99_900), true, "old", fri, fri),
        )
        val b = IouRules.balances(ious)
        assertEquals(listOf("Ravi", "Priya"), b.map { it.person })
        assertEquals(30_000L, b[0].netPaise)
        assertEquals(Paise(30_000), IouRules.owedToMe(ious))
        assertEquals(Paise(10_000), IouRules.iOwe(ious))
    }
}
