package app.assistant.domain.engine.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HorizonPlannerTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private fun fallback(g: HorizonGoal) = listOf(HorizonStep(g.key, "Start ${g.name}", StepSize.S), HorizonStep(g.key, "Work on ${g.name}", StepSize.L))

    @Test
    fun aWeekOfTenHoursOverFiveDaysNeedsTwoHoursADay() {
        val plan = HorizonPlanner.plan(
            Horizon.WEEK,
            monday,
            listOf(HorizonGoal("g1", "Report", 6, 1), HorizonGoal("g2", "Course", 4, 2)),
            emptyList(),
            daysPerWeek = 5,
            maxDailyMinutes = 180,
            fallback = ::fallback,
        )
        assertEquals(120, plan.neededDailyMinutes)
        assertTrue(plan.feasible)
        assertEquals(600, plan.sessions.sumOf { it.minutes })
        // Mon–Fri only, and finished inside the week.
        assertTrue(plan.sessions.all { it.date.dayOfWeek.value <= 5 })
        assertEquals(monday.plusDays(4), plan.finish)
        // Each work day stays within the daily time.
        plan.sessions.groupBy { it.date }.values.forEach { assertTrue(it.sumOf { s -> s.minutes } <= 120) }
    }

    @Test
    fun bothGoalsMoveForwardOnTheFirstDay() {
        val plan = HorizonPlanner.plan(
            Horizon.WEEK,
            monday,
            listOf(HorizonGoal("g1", "A", 5, 1), HorizonGoal("g2", "B", 5, 2)),
            emptyList(),
            7,
            240,
            ::fallback,
        )
        val first = plan.sessions.filter { it.date == monday }.map { it.goalKey }.toSet()
        assertEquals(setOf("g1", "g2"), first)
    }

    @Test
    fun tooMuchWorkKeepsToTheDailyCapAndFinishesLater() {
        val plan = HorizonPlanner.plan(Horizon.WEEK, monday, listOf(HorizonGoal("g1", "Big", 20, 1)), emptyList(), 5, 120, ::fallback)
        assertFalse(plan.feasible)
        assertEquals(240, plan.neededDailyMinutes)
        assertEquals(120, plan.dailyMinutes)
        assertTrue(plan.finish!!.isAfter(plan.end))
        assertEquals(1200, plan.sessions.sumOf { it.minutes })
    }

    @Test
    fun aiStepsAreUsedAndLongStepsAreSplitIntoSessions() {
        val steps = listOf(HorizonStep("g1", "Outline", StepSize.S), HorizonStep("g1", "Write chapters", StepSize.L))
        val plan = HorizonPlanner.plan(Horizon.MONTH, monday, listOf(HorizonGoal("g1", "Book", 8, 1)), steps, 6, 120, ::fallback)
        assertTrue(plan.sessions.any { it.title.startsWith("Outline") })
        assertTrue(plan.sessions.any { it.title.startsWith("Write chapters") })
        assertTrue(plan.sessions.all { it.minutes <= HorizonPlanner.SESSION_MAX })
        assertEquals(480, plan.sessions.sumOf { it.minutes })
    }

    @Test
    fun catchUpFillsDaysWithRoomFirst() {
        val load = mapOf(monday to 120, monday.plusDays(1) to 30)
        val moved = HorizonPlanner.catchUp(
            listOf(MissedSession(1, 60, monday.plusDays(4)), MissedSession(2, 60, monday.plusDays(4))),
            load,
            monday,
            daysPerWeek = 5,
            daily = 120,
        )
        assertEquals(monday.plusDays(1), moved[1])
        assertEquals(monday.plusDays(2), moved[2])
    }

    @Test
    fun windowsAreRolling() {
        assertEquals(LocalDate.of(2026, 11, 8), HorizonPlanner.end(Horizon.WEEK, monday))
        assertEquals(LocalDate.of(2026, 12, 1), HorizonPlanner.end(Horizon.MONTH, monday))
        assertEquals(LocalDate.of(2027, 11, 1), HorizonPlanner.end(Horizon.YEAR, monday))
    }
}
