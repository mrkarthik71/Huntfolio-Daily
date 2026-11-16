package app.assistant.domain.engine.schedule

import app.assistant.domain.model.EnergyTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class PlannersTest {
    private val today = LocalDate.of(2026, 11, 18) // Wednesday
    private val working = Span(9 * 60, 18 * 60)
    private val deep = PlanTask(1, "Write the proposal", 90, 1, EnergyTag.DEEP_WORK, today)
    private val admin = PlanTask(2, "Pay bills", 30, 3, EnergyTag.LIGHT_ADMIN, null)

    @Test
    fun `plan my day fills free time from now and skips meetings`() {
        val p = DayPlanner.propose(listOf(deep, admin), today, nowMinute = 9 * 60 + 5, working = working, busy = listOf(Span(10 * 60, 11 * 60)))
        assertTrue(p.blocks.all { it.span.start >= 9 * 60 + 15 })
        assertTrue(p.blocks.none { it.span.overlaps(Span(10 * 60, 11 * 60)) })
        assertEquals(setOf(1L, 2L), p.blocks.map { it.taskId }.toSet())
    }

    @Test
    fun `after a short night deep work starts after 14 00`() {
        val p = DayPlanner.propose(listOf(deep), today, nowMinute = 9 * 60, working = working, busy = emptyList(), sleepMinutes = 5 * 60)
        assertTrue(p.lowSleep)
        assertTrue(p.blocks.all { it.span.start >= 14 * 60 })
    }

    @Test
    fun `nothing is placed after working hours`() {
        val p = DayPlanner.propose(listOf(deep), today, nowMinute = 19 * 60, working = working, busy = emptyList())
        assertTrue(p.blocks.isEmpty())
    }

    @Test
    fun `week plan picks the top three and keeps due dates`() {
        val tasks = listOf(
            deep.copy(due = today.plusDays(6)), // due next Tuesday
            admin,
            PlanTask(3, "Gym plan", 45, 2, EnergyTag.PHYSICAL_ROUTINE, null),
            PlanTask(4, "Low", 30, 5, EnergyTag.LIGHT_ADMIN, null),
        )
        val w = WeekPlanner.propose(tasks, today, listOf("Read 12 books"))
        assertEquals(LocalDate.of(2026, 11, 23), w.weekStart)
        assertEquals(listOf(1L, 3L, 2L).toSet(), w.top.map { it.task.id }.toSet())
        assertTrue(w.top.all { it.day.dayOfWeek != DayOfWeek.SATURDAY && it.day.dayOfWeek != DayOfWeek.SUNDAY })
        assertTrue(w.top.first { it.task.id == 1L }.day <= today.plusDays(6))
        assertEquals(3, w.top.map { it.day }.distinct().size)
    }
}
