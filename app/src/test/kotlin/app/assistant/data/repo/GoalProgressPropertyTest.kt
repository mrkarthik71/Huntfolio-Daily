// Feature: personal-assistant-android, Property 10: Goal progress is kept per Period_Instance
package app.assistant.data.repo

import android.app.Application
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.engine.goals.QuantityParser
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.Quantity
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private sealed interface ProgressOp {
    data class Log(val hundredths: Long, val dayOffset: Int) : ProgressOp

    data class Complete(val dayOffset: Int) : ProgressOp

    data class Edit(val target: Int) : ProgressOp

    object Archive : ProgressOp
}

private data class ProgressScenario(val period: GoalPeriod, val unit: String, val ops: List<ProgressOp>)

/**
 * Property 10 on the real repositories (Requirements 11.1, 11.4, 11.5, 11.6, 11.9): after any sequence of progress logs,
 * linked-Task completions (unit "tasks" in any letter case), edits and archiving, each Period_Instance holds exactly the
 * logged amounts plus 1 per linked Task completed in it; edits and archiving change no progress record; an archived Goal
 * accepts nothing more and is in no active list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoalProgressPropertyTest {
    private lateinit var rig: Phase1Rig

    @Before
    fun setUp() {
        rig = Phase1Rig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private val opArb = arbitrary {
        when (Arb.int(0..9).bind()) {
            in 0..3 -> ProgressOp.Log(Arb.element(listOf(50L, 100L, 250L, 1_999L)).bind(), Arb.int(-40..40).bind())
            in 4..6 -> ProgressOp.Complete(Arb.int(-40..40).bind())
            in 7..8 -> ProgressOp.Edit(Arb.int(1..50_000).bind())
            else -> ProgressOp.Archive
        }
    }

    private val scenarioArb = arbitrary {
        ProgressScenario(
            Arb.element(GoalPeriod.entries).bind(),
            Arb.element(listOf("tasks", "Tasks", "TASKS", " tasks ", "hours")).bind(),
            Arb.list(opArb, 1..12).bind(),
        )
    }

    private suspend fun stored(): Map<String, Long> = rig.db.goalDao().allProgressNow().associate { it.periodFirstDay to it.progressHundredths }

    @Test
    fun `Property 10 - progress per Period_Instance`() {
        runBlocking {
            checkAll(150, scenarioArb) { s ->
                rig.reset()
                val today = rig.time.today()
                val now = rig.time.now()
                val lastDay = rig.settings.current().lastDayOfWeek
                val start = today.minusDays(60)
                val goal = (rig.goals.save(GoalInput("G", s.period, start, "10", s.unit)) as Outcome.Ok).value
                val countsTasks = s.unit.trim().equals("tasks", ignoreCase = true)
                val expected = HashMap<String, Long>()
                var archived = false

                fun add(dayOffset: Int, hundredths: Long) {
                    val key = Periods.instanceContaining(s.period, today.plusDays(dayOffset.toLong()), lastDay).first.toString()
                    expected[key] = (expected[key] ?: 0L) + hundredths
                }

                for (op in s.ops) {
                    when (op) {
                        is ProgressOp.Log -> {
                            val result = rig.goals.logProgress(goal, QuantityParser.format(Quantity(op.hundredths)), today.plusDays(op.dayOffset.toLong()))
                            if (archived) {
                                assertEquals(Outcome.Err(ValidationError.NOT_ALLOWED), result)
                            } else {
                                assertEquals(Outcome.Ok(Unit), result)
                                add(op.dayOffset, op.hundredths)
                            }
                        }
                        is ProgressOp.Complete -> {
                            val id = (rig.tasks.save(TaskInput("t", goalId = goal), null, now, today) as Outcome.Ok).value
                            rig.tasks.markDone(id, now, today.plusDays(op.dayOffset.toLong()))
                            if (!archived && countsTasks) add(op.dayOffset, 100L)
                        }
                        is ProgressOp.Edit -> {
                            val edited = GoalInput("G${op.target}", s.period, start, op.target.toString(), s.unit)
                            assertTrue(rig.goals.save(edited, goal) is Outcome.Ok)
                        }
                        ProgressOp.Archive -> {
                            rig.goals.archive(goal, now)
                            archived = true
                        }
                    }
                    assertEquals(expected, stored())
                }
                if (archived) {
                    assertTrue(rig.goals.statuses(today).first().isEmpty())
                    assertTrue(rig.goals.statusesNow(today).isEmpty())
                }
            }
        }
    }
}
