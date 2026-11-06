package app.assistant.domain.engine.life

import app.assistant.domain.model.DateKind
import app.assistant.domain.model.Habit
import app.assistant.domain.model.HabitCheck
import app.assistant.domain.model.HabitKind
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SavingsEntry
import app.assistant.domain.model.SavingsGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class LifeRulesTest {
    private val today = LocalDate.of(2026, 11, 18) // a Wednesday
    private val am = LocalTime.of(8, 0)
    private val pm = LocalTime.of(21, 0)
    private val pills = Habit(1, "Vitamin D", HabitKind.MEDICINE, listOf(pm, am), DayOfWeek.entries.toSet(), true, today.minusDays(30))

    private fun both(d: LocalDate) = listOf(HabitCheck(1, d, am), HabitCheck(1, d, pm))

    @Test
    fun `habit slots follow days, creation date and active flag`() {
        assertEquals(listOf(am, pm), HabitRules.slotsOn(pills, today))
        assertTrue(HabitRules.slotsOn(pills, today.minusDays(31)).isEmpty())
        assertTrue(HabitRules.slotsOn(pills.copy(active = false), today).isEmpty())
        val weekdays = pills.copy(days = setOf(DayOfWeek.MONDAY))
        assertTrue(HabitRules.slotsOn(weekdays, today).isEmpty())
    }

    @Test
    fun `streak counts fully done days and an unfinished today does not break it`() {
        val checks = both(today.minusDays(1)) + both(today.minusDays(2)) + listOf(HabitCheck(1, today.minusDays(3), am))
        assertEquals(2, HabitRules.streak(pills, checks, today))
        assertEquals(3, HabitRules.streak(pills, checks + both(today), today))
        assertEquals(1 to 2, HabitRules.dayProgress(listOf(pills), listOf(HabitCheck(1, today, am)), today))
    }

    @Test
    fun `yearly dates roll to next year and 29 Feb falls on 28 Feb`() {
        val bday = ImportantDate(1, "Mom", DateKind.BIRTHDAY, LocalDate.of(1965, 3, 4), yearly = true)
        assertEquals(LocalDate.of(2027, 3, 4), DateRules.nextOccurrence(bday, today))
        assertEquals(62, DateRules.yearsAt(bday, today))
        val leap = ImportantDate(2, "Leap", DateKind.BIRTHDAY, LocalDate.of(2000, 2, 29), yearly = true)
        assertEquals(LocalDate.of(2027, 2, 28), DateRules.nextOccurrence(leap, today))
        val noYear = bday.copy(date = LocalDate.of(DateRules.NO_YEAR, 3, 4))
        assertNull(DateRules.yearsAt(noYear, today))
    }

    @Test
    fun `documents are reminded 30, 7, 1 and 0 days ahead and past one-offs drop`() {
        val passport = ImportantDate(3, "Passport", DateKind.DOCUMENT, today.plusDays(30), yearly = false)
        val alerts = DateRules.alertsBetween(listOf(passport), today, today.plusDays(1))
        assertEquals(listOf(30), alerts.map { it.leadDays })
        assertEquals("date:3:${today.plusDays(30)}:30", alerts.single().refKey)
        assertNull(DateRules.nextOccurrence(passport.copy(date = today.minusDays(1)), today))
    }

    @Test
    fun `savings pace spreads what is left over the months to the deadline`() {
        val trip = SavingsGoal(1, "Goa trip", Paise(5_000_000), today.plusMonths(4), today)
        val entries = listOf(SavingsEntry(1, 1, Paise(1_000_000), today))
        assertEquals(Paise(1_000_000), SavingsRules.saved(trip, entries))
        assertEquals(0.2f, SavingsRules.progress(trip, entries))
        assertEquals(Paise(1_000_000), SavingsRules.monthlyNeeded(trip, entries, today))
        assertNull(SavingsRules.monthlyNeeded(trip.copy(deadline = null), entries, today))
    }

    @Test
    fun `budget alerts fire once at 80 and once at 100 percent`() {
        val budget = Paise(1_000_000)
        assertEquals(80, BudgetAlertRules.crossed(budget, Paise(700_000), Paise(850_000)))
        assertNull(BudgetAlertRules.crossed(budget, Paise(850_000), Paise(900_000)))
        assertEquals(100, BudgetAlertRules.crossed(budget, Paise(700_000), Paise(1_100_000)))
        assertNull(BudgetAlertRules.crossed(Paise(0), Paise(0), Paise(10)))
    }
}
