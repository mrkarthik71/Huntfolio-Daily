// Feature: personal-assistant-android, Property 18: Plan contents and override persistence
package app.assistant.data.repo

import android.app.Application
import app.assistant.domain.engine.plan.PlanBuilder
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.TaskId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
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

private sealed interface PlanOp {
    /** The User sets the Priorities to the Tasks at these positions (taken modulo the number of Tasks). */
    data class SetOrder(val picks: List<Int>) : PlanOp

    data class Remove(val pick: Int) : PlanOp

    /** A new Task appears and the plan for the date is asked for again. */
    data class AddTask(val estimate: Int?) : PlanOp
}

private data class PlanScenario(val estimates: List<Int?>, val ops: List<PlanOp>)

/**
 * Property 18 on the real repositories (Requirements 14.6, 14.7): after any sequence of User reorders, replacements and
 * removals of Priorities, each followed by a request for the plan of the same date, the stored plan is exactly the User's
 * choice, and a removed Priority has no Time_Block. A stored plan is never rebuilt over the User's changes.
 *
 * The Bill and Behind_Goal parts of Requirement 14.6 are computed when the plan is shown, not stored in the Daily_Plan;
 * the Bill part arrives with the Bill_Tracker (task 22.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlanOverridePropertyTest {
    private lateinit var rig: Phase1Rig

    @Before
    fun setUp() {
        rig = Phase1Rig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private val estimateArb = Arb.element(listOf(null, 30, 45, 90, 180))

    // Built from choice/bind rather than the arbitrary {} builder, whose edge-case sampling could
    // hit an empty collection and fail the run at random.
    private val opArb: Arb<PlanOp> = Arb.choice(
        Arb.list(Arb.int(0..20), 0..5).map { PlanOp.SetOrder(it) },
        Arb.list(Arb.int(0..20), 0..5).map { PlanOp.SetOrder(it) },
        Arb.int(0..20).map { PlanOp.Remove(it) },
        estimateArb.map { PlanOp.AddTask(it) },
    )

    private val scenarioArb: Arb<PlanScenario> =
        Arb.bind(Arb.list(estimateArb, 2..6), Arb.list(opArb, 1..10)) { estimates, ops -> PlanScenario(estimates, ops) }

    private suspend fun addTask(estimate: Int?): TaskId {
        val input = TaskInput("t", plannedDate = rig.time.today(), estimateText = estimate?.toString().orEmpty())
        return (rig.tasks.save(input, null, rig.time.now(), rig.time.today()) as Outcome.Ok).value
    }

    @Test
    fun `Property 18 - user changes to the plan persist and removed priorities lose their block`() {
        runBlocking {
            // Random cases only: kotest's edge-case sampling for these nested lists intermittently
            // threw NoSuchElementException inside the generator itself.
            val config = io.kotest.property.PropTestConfig(iterations = 150, edgeConfig = io.kotest.property.EdgeConfig(edgecasesGenerationProbability = 0.0))
            checkAll(config, scenarioArb) { s ->
                rig.reset()
                val today = rig.time.today()
                val ids = ArrayList<TaskId>()
                for (e in s.estimates) ids += addTask(e)

                // All Tasks share one creation time, so the automatic ranking falls back to Task ID, which is creation order.
                var model: List<TaskId> = ids.take(PlanBuilder.MAX_PRIORITIES)
                assertEquals(model, rig.plans.ensurePlanFor(today).plan.priorities)

                for (op in s.ops) {
                    when (op) {
                        is PlanOp.SetOrder -> {
                            val chosen = op.picks.map { ids[it % ids.size] }
                            rig.plans.setPriorities(today, chosen)
                            model = chosen.distinct().take(PlanBuilder.MAX_PRIORITIES)
                        }
                        is PlanOp.Remove -> {
                            val id = ids[op.pick % ids.size]
                            rig.plans.removePriority(today, id)
                            model = model - id
                            assertEquals(0, rig.count("SELECT COUNT(*) FROM time_block WHERE task_id = ? AND date = ?", id.value, today.toString()))
                        }
                        is PlanOp.AddTask -> ids += addTask(op.estimate)
                    }
                    val plan = rig.plans.ensurePlanFor(today).plan
                    assertEquals(model, plan.priorities)
                    val blockTasks = plan.timeBlocks.mapNotNull { it.taskId }
                    assertTrue(model.containsAll(blockTasks))
                    assertEquals(blockTasks.size, blockTasks.distinct().size)
                }
            }
        }
    }
}
