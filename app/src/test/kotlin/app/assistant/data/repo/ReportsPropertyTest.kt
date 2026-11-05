// Feature: personal-assistant-android, Property 20: Reports are a function of data and period (weekly progress report)
package app.assistant.data.repo

import android.app.Application
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.engine.goals.QuantityParser
import app.assistant.domain.engine.review.WeekGoalRow
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.Quantity
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** A Goal to create: its period, start, target in hundredths, optional archive day and the (day offset, hundredths) progress logs. */
private data class ReportGoalSpec(val weekly: Boolean, val startOffset: Int, val target: Long, val archivedOffset: Int?, val logs: List<Pair<Int, Long>>)

/** A Task and the day offsets on which it is moved. */
private data class ReportTaskSpec(val moveDays: List<Int>)

private data class ReportScenario(val goals: List<ReportGoalSpec>, val tasks: List<ReportTaskSpec>, val queryOffset: Int, val catchUpDays: Int)

/**
 * Property 20, weekly progress report part, on the real repositories (Requirements 17.5, 17.6): the report for a week equals
 * a reference calculation over the stored data of that week: exactly the week Goals active during the week with the progress
 * of that week, and exactly the Tasks moved two or more times in the week. Creating the reports late (catch-up) creates one
 * report for every ended week, once, and leaves the content unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReportsPropertyTest {
    private lateinit var rig: Phase1Rig

    @Before
    fun setUp() {
        rig = Phase1Rig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private val goalArb = arbitrary {
        ReportGoalSpec(
            weekly = Arb.boolean().bind(),
            startOffset = Arb.int(-30..10).bind(),
            target = Arb.element(listOf(100L, 500L, 2_500L)).bind(),
            archivedOffset = Arb.int(-30..30).orNull(0.6).bind(),
            logs = Arb.list(arbitrary { Arb.int(-30..30).bind() to Arb.element(listOf(50L, 100L, 250L)).bind() }, 0..5).bind(),
        )
    }

    private val scenarioArb = arbitrary {
        ReportScenario(
            goals = Arb.list(goalArb, 0..4).bind(),
            tasks = Arb.list(arbitrary { ReportTaskSpec(Arb.list(Arb.int(-30..30), 0..4).bind()) }, 1..4).bind(),
            queryOffset = Arb.int(-30..30).bind(),
            catchUpDays = Arb.int(0..60).bind(),
        )
    }

    @Test
    fun `Property 20 - weekly progress report equals the reference over stored data`() {
        runBlocking {
            checkAll(100, scenarioArb) { s ->
                rig.reset()
                val today = rig.time.today()
                val zone = rig.time.zone()
                val lastDay = rig.settings.current().lastDayOfWeek
                fun day(offset: Int): LocalDate = today.plusDays(offset.toLong())

                s.goals.forEachIndexed { i, g ->
                    val period = if (g.weekly) GoalPeriod.WEEK else GoalPeriod.MONTH
                    val input = GoalInput("g$i", period, day(g.startOffset), QuantityParser.format(Quantity(g.target)), "pts")
                    val id = (rig.goals.save(input) as Outcome.Ok).value
                    for ((offset, hundredths) in g.logs) {
                        assertEquals(Outcome.Ok(Unit), rig.goals.logProgress(id, QuantityParser.format(Quantity(hundredths)), day(offset)))
                    }
                    g.archivedOffset?.let { rig.goals.archive(id, day(it).atTime(12, 0).atZone(zone).toInstant()) }
                }
                s.tasks.forEachIndexed { i, t ->
                    val id = (rig.tasks.save(TaskInput("t$i"), null, rig.time.now(), today) as Outcome.Ok).value
                    t.moveDays.forEachIndexed { j, offset ->
                        // Each target is 400 days apart, so no move is a move to the date the Task already has.
                        val moved = rig.tasks.move(id, day(offset).plusDays(400L * (j + 1)), day(offset))
                        assertTrue(moved is Outcome.Ok)
                    }
                }

                val week = Periods.instanceContaining(GoalPeriod.WEEK, day(s.queryOffset), lastDay)
                fun inWeek(d: LocalDate): Boolean = !d.isBefore(week.first) && !d.isAfter(week.last)

                val expectedGoals = s.goals.withIndex()
                    .filter { (_, g) -> g.weekly && !day(g.startOffset).isAfter(week.last) }
                    .filter { (_, g) -> g.archivedOffset == null || !day(g.archivedOffset).isBefore(week.first) }
                    .map { (i, g) ->
                        val progress = g.logs
                            .filter { (offset, _) -> Periods.instanceContaining(GoalPeriod.WEEK, day(offset), lastDay).first == week.first }
                            .sumOf { it.second }
                        WeekGoalRow("g$i", Quantity(g.target), Quantity(progress), "pts")
                    }
                    .sortedBy { it.name.lowercase() }
                val expectedSlipped = s.tasks.withIndex()
                    .map { (i, t) -> "t$i" to t.moveDays.count { inWeek(day(it)) } }
                    .filter { it.second >= 2 }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

                val report = rig.reports.weeklyProgress(week)
                assertEquals(week, report.week)
                assertEquals(expectedGoals, report.goals)
                assertEquals(expectedSlipped, report.slipped)

                // Catch-up: one report for every ended week, created once, and the content does not change.
                val later = day(s.catchUpDays)
                rig.reports.ensureReportsUpTo(later)
                rig.reports.ensureReportsUpTo(later)
                val currentWeek = Periods.instanceContaining(GoalPeriod.WEEK, later, lastDay)
                val expectedWeeks = generateSequence(today) { it.plusDays(1) }
                    .takeWhile { it.isBefore(currentWeek.first) }
                    .map { Periods.instanceContaining(GoalPeriod.WEEK, it, lastDay).first.toString() }
                    .toSet()
                val stored = rig.strings("SELECT period_first_day FROM report WHERE kind = 'WEEKLY_PROGRESS'")
                assertEquals(expectedWeeks, stored.toSet())
                assertEquals(stored.size, stored.toSet().size)
                assertEquals(report, rig.reports.weeklyProgress(week))
            }
        }
    }
}
