// Feature: personal-assistant-android, Property 14: Task completion, move and delete
package app.assistant.data.repo

import android.app.Application
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.plan.PlanBuilder
import app.assistant.domain.engine.tasks.MoveError
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

private enum class TaskAction { DONE, MOVE, DELETE }

private data class TaskSpec(val plannedOffset: Int?, val estimate: Int?, val linked: Boolean)

private data class TaskScenario(val specs: List<TaskSpec>, val target: Int, val action: TaskAction, val moveOffset: Int, val doneFirst: Boolean)

/**
 * Property 14 on the real repositories (Requirements 12.3, 12.4, 12.5, 12.7): marking a Task done takes it out of the open
 * list and the Priority selection; a move to a different date that is not in the past sets the Planned_Date, records exactly
 * one Task_Move and removes the Task from the plans of the dates it leaves; a move to the past or to the same date changes
 * nothing; deleting removes the Task and its Time_Blocks and leaves Goal progress as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TaskLifecyclePropertyTest {
    private lateinit var rig: Phase1Rig

    @Before
    fun setUp() {
        rig = Phase1Rig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private val specArb = arbitrary {
        TaskSpec(Arb.int(0..2).orNull(0.2).bind(), Arb.element(listOf(null, 30, 60)).bind(), Arb.boolean().bind())
    }

    private val scenarioArb = arbitrary {
        val specs = Arb.list(specArb, 1..5).bind()
        TaskScenario(specs, Arb.int(0 until specs.size).bind(), Arb.element(TaskAction.entries).bind(), Arb.int(-3..5).bind(), Arb.boolean().bind())
    }

    private fun rowsAt(id: TaskId, date: LocalDate): Int = rig.count(
        "SELECT (SELECT COUNT(*) FROM plan_priority WHERE task_id = ? AND date = ?) + " +
            "(SELECT COUNT(*) FROM time_block WHERE task_id = ? AND date = ?)",
        id.value,
        date.toString(),
        id.value,
        date.toString(),
    )

    private fun allPlanRows(): Int = rig.count("SELECT (SELECT COUNT(*) FROM plan_priority) + (SELECT COUNT(*) FROM time_block)")

    @Test
    fun `Property 14 - completion, move and delete`() {
        runBlocking {
            checkAll(150, scenarioArb) { s ->
                rig.reset()
                val today = rig.time.today()
                val now = rig.time.now()
                val goal = (rig.goals.save(GoalInput("G", GoalPeriod.WEEK, today.minusDays(30), "1000", "Tasks")) as Outcome.Ok).value
                val ids = s.specs.map { spec ->
                    val input = TaskInput(
                        title = "t",
                        plannedDate = spec.plannedOffset?.let { today.plusDays(it.toLong()) },
                        estimateText = spec.estimate?.toString().orEmpty(),
                        goalId = goal.takeIf { spec.linked },
                    )
                    (rig.tasks.save(input, null, now, today) as Outcome.Ok).value
                }
                val id = ids[s.target]
                val linked = s.specs[s.target].linked
                val before = checkNotNull(rig.tasks.get(id))
                rig.plans.ensurePlanFor(today)
                before.plannedDate?.let { rig.plans.ensurePlanFor(it) }

                when (s.action) {
                    TaskAction.DONE -> {
                        val progressBefore = rig.progressTotal()
                        rig.tasks.markDone(id, now, today)
                        assertEquals(TaskStatus.DONE, checkNotNull(rig.tasks.get(id)).status)
                        val open = rig.tasks.openTasks().first()
                        assertTrue(open.none { it.id == id })
                        assertFalse(PlanBuilder.rank(open, today, emptySet()).any { it.id == id })
                        assertEquals(progressBefore + if (linked) 100L else 0L, rig.progressTotal())
                    }
                    TaskAction.MOVE -> {
                        val to = today.plusDays(s.moveOffset.toLong())
                        val planRowsBefore = allPlanRows()
                        val result = rig.tasks.move(id, to, today)
                        val after = checkNotNull(rig.tasks.get(id))
                        val moves = rig.count("SELECT COUNT(*) FROM task_move WHERE task_id = ?", id.value)
                        if (to.isBefore(today) || to == before.plannedDate) {
                            val expected = if (to.isBefore(today)) MoveError.IN_PAST else MoveError.SAME_DATE
                            assertEquals(Outcome.Err(expected), result)
                            assertEquals(before, after)
                            assertEquals(0, moves)
                            assertEquals(planRowsBefore, allPlanRows())
                        } else {
                            assertEquals(Outcome.Ok(Unit), result)
                            assertEquals(to, after.plannedDate)
                            assertEquals(1, moves)
                            for (d in listOfNotNull(before.plannedDate, today).filter { it != to }.distinct()) assertEquals(0, rowsAt(id, d))
                        }
                    }
                    TaskAction.DELETE -> {
                        if (s.doneFirst) rig.tasks.markDone(id, now, today)
                        val progressBefore = rig.progressTotal()
                        rig.tasks.delete(id)
                        assertNull(rig.tasks.get(id))
                        assertTrue(rig.tasks.openTasks().first().none { it.id == id })
                        assertEquals(0, rig.count("SELECT COUNT(*) FROM plan_priority WHERE task_id = ?", id.value))
                        assertEquals(0, rig.count("SELECT COUNT(*) FROM time_block WHERE task_id = ?", id.value))
                        assertEquals(progressBefore, rig.progressTotal())
                    }
                }
            }
        }
    }
}
