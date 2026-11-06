package app.assistant.domain.engine.schedule

import app.assistant.domain.model.CardKind
import app.assistant.domain.model.EnergyTag
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ScheduleEnginesTest {

    private val day = Span(8 * 60, 20 * 60)

    @Test
    fun `free time is the day minus busy spans, merged and in order`() {
        val free = FreeTime.of(day, listOf(Span(10 * 60, 11 * 60), Span(10 * 60 + 30, 12 * 60), Span(15 * 60, 16 * 60)))
        assertEquals(listOf(Span(480, 600), Span(720, 900), Span(960, 1200)), free)
    }

    @Test
    fun `urgent high-priority deep work gets the first morning deep-work slot`() {
        val work = listOf(
            Work(1, 60, priority = 4, energy = EnergyTag.LIGHT_ADMIN, dueInDays = null),
            Work(2, 90, priority = 1, energy = EnergyTag.DEEP_WORK, dueInDays = 0),
        )
        val a = SlotAssigner.assign(work, listOf(day))
        val first = a.placements.first { it.workId == 2L }
        assertEquals(9 * 60, maxOf(first.span.start, 9 * 60)) // inside the morning window
        assertTrue(a.unplacedMinutes.isEmpty())
    }

    @Test
    fun `long work is split into chunks of at most two hours`() {
        val a = SlotAssigner.assign(listOf(Work(1, 300, 2, EnergyTag.DEEP_WORK, 3)), listOf(day))
        assertTrue(a.placements.all { it.span.minutes <= SlotAssigner.MAX_CHUNK })
        assertEquals(300, a.placements.sumOf { it.span.minutes })
    }

    @Test
    fun `work that does not fit is reported, never dropped`() {
        val a = SlotAssigner.assign(listOf(Work(1, 200, 1, EnergyTag.DEEP_WORK, 0)), listOf(Span(600, 660)))
        assertEquals(60, a.placements.sumOf { it.span.minutes })
        assertEquals(mapOf(1L to 140), a.unplacedMinutes)
    }

    @Test
    fun `property - placements never overlap, stay inside free time, and account for every minute`() = runBlocking {
        val workArb = Arb.bind(Arb.int(1..20), Arb.int(25..240), Arb.int(1..5), Arb.element(EnergyTag.entries.toList()), Arb.int(-1..10)) { id, m, p, e, d ->
            Work(id.toLong(), m, p, e, d.takeIf { it >= 0 })
        }
        checkAll(PropertyDefaults.standard, Arb.list(workArb, 0..8), Arb.list(Arb.int(8 * 60..19 * 60), 0..4)) { raw, busyStarts ->
            val work = raw.distinctBy { it.id }
            val busy = busyStarts.map { Span(it, minOf(it + 45, 20 * 60)) }
            val free = FreeTime.of(day, busy)
            val a = SlotAssigner.assign(work, free)
            val sorted = a.placements.sortedBy { it.span.start }
            sorted.zipWithNext().forEach { (x, y) -> assertTrue(x.span.end <= y.span.start, "overlap $x $y") }
            sorted.forEach { p -> assertTrue(free.any { it.start <= p.span.start && p.span.end <= it.end }, "outside free time $p") }
            work.forEach { w ->
                val placed = a.placements.filter { it.workId == w.id }.sumOf { it.span.minutes }
                assertEquals(maxOf(w.minutes, SlotAssigner.MIN_CHUNK), placed + (a.unplacedMinutes[w.id] ?: 0), "minutes lost for $w")
            }
        }
    }

    @Test
    fun `day load rounds to a whole percent and flags a booked day with no free time`() {
        assertEquals(86, DayLoad.percent(310, 360))
        assertEquals(0, DayLoad.percent(0, 0))
        assertTrue(DayLoad.percent(30, 0) > DayLoad.OVERLOADED)
    }

    @Test
    fun `rescheduling keeps the morning and moves only later work`() {
        val work = listOf(Work(1, 60, 2, EnergyTag.DEEP_WORK, 1), Work(2, 60, 3, EnergyTag.LIGHT_ADMIN, null))
        val plan = SlotAssigner.assign(work, listOf(day)).placements
        val overrunUntil = plan.maxOf { it.span.start } + 30
        val diff = Rescheduler.reshuffle(plan, work, day, busy = listOf(Span(plan.maxOf { it.span.start }, overrunUntil)), from = plan.maxOf { it.span.start })
        assertTrue(diff.unchanged.any { it.span.start < plan.maxOf { p -> p.span.start } })
        assertTrue(diff.moved.all { (old, new) -> new.span.start >= old.span.start })
    }

    @Test
    fun `proactive rules raise overload, goal at risk, renewal and free slot cards`() {
        val today = LocalDate.of(2026, 11, 5)
        val signals = ProactiveRules.evaluate(
            today = today,
            days = listOf(DayFacts(today, 200, 420, 120), DayFacts(today.plusDays(1), 560, 420, 30)),
            goals = listOf(GoalFacts(1, "Read 12 books", today.plusDays(3), 2_000)),
            renewals = listOf(RenewalFacts("StreamFlix", "₹649", today.plusDays(2))),
            averageFreeMinutesPerDay = 240,
            openDeepWork = true,
        )
        assertEquals(setOf(CardKind.OVERLOAD, CardKind.GOAL_AT_RISK, CardKind.RENEWAL_SOON, CardKind.FREE_SLOT), signals.map { it.kind }.toSet())
        assertEquals(listOf(560, 420), signals.first { it.kind == CardKind.OVERLOAD }.numbers)
    }

    @Test
    fun `pace plan lands on the deadline and reaches the target`() {
        val today = LocalDate.of(2026, 11, 5)
        val steps = PacePlanner.plan(today, LocalDate.of(2026, 12, 31), target = 12, alreadyDone = 7)
        assertEquals(LocalDate.of(2026, 12, 31), steps.last().dueDate)
        assertEquals(12L, steps.last().cumulativeTarget)
        assertTrue(steps.zipWithNext().all { (a, b) -> a.cumulativeTarget <= b.cumulativeTarget && a.dueDate < b.dueDate })
        assertTrue(PacePlanner.plan(today, today, 12, 0).isEmpty())
    }
}
