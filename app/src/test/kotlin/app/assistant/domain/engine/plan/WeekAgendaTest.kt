package app.assistant.domain.engine.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekAgendaTest {
    private val mon = LocalDate.of(2026, 11, 16)

    @Test
    fun timedItemsComeFirstInTimeOrderThenTheRestByKind() {
        val items = listOf(
            AgendaItem(AgendaKind.BILL, "Rent", mon),
            AgendaItem(AgendaKind.MEETING, "Standup", mon, LocalTime.of(9, 30), 15),
            AgendaItem(AgendaKind.TRIP, "12627", mon, LocalTime.of(6, 0)),
            AgendaItem(AgendaKind.TASK, "Call bank", mon, minutes = 30),
        )
        val day = AgendaRules.week(mon, items).first()
        assertEquals(listOf("12627", "Standup", "Call bank", "Rent"), day.items.map { it.title })
        assertEquals(45, day.busyMinutes)
        assertEquals(1, day.dueCount)
    }

    @Test
    fun sevenDaysAndTheBusiestOne() {
        val items = listOf(
            AgendaItem(AgendaKind.STEP, "Course", mon.plusDays(3), minutes = 300),
            AgendaItem(AgendaKind.MEETING, "Review", mon.plusDays(3), LocalTime.of(14, 0), 240),
            AgendaItem(AgendaKind.TASK, "Done one", mon.plusDays(1), minutes = 600, done = true),
        )
        val days = AgendaRules.week(mon, items)
        assertEquals(7, days.size)
        assertEquals(mon.plusDays(3), AgendaRules.busiest(days)!!.date)
        assertEquals(listOf(mon.plusDays(3)), AgendaRules.overloaded(days).map { it.date })
        assertEquals(1f, days[3].load)
        assertEquals(0, days[1].busyMinutes)
    }

    @Test
    fun anEmptyWeekHasNoBusiestDay() {
        assertNull(AgendaRules.busiest(AgendaRules.week(mon, emptyList())))
    }
}
