package app.assistant.data.repo

import android.app.Application
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.TaskId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only open Tasks can be stored as Priorities (Requirement 14.7): a done or deleted Task id is ignored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlanNonOpenPriorityTest {
    private lateinit var rig: Phase1Rig

    @Before
    fun setUp() {
        rig = Phase1Rig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private suspend fun addTask(title: String): TaskId {
        val input = TaskInput(title, plannedDate = rig.time.today())
        return (rig.tasks.save(input, null, rig.time.now(), rig.time.today()) as Outcome.Ok).value
    }

    @Test
    fun `done and deleted tasks are never stored as priorities`() {
        runBlocking {
            val today = rig.time.today()
            val open = addTask("open")
            val done = addTask("done")
            val deleted = addTask("deleted")
            rig.tasks.markDone(done, rig.time.now(), today)
            rig.tasks.delete(deleted)

            rig.plans.setPriorities(today, listOf(done, deleted, open))

            assertEquals(listOf(open), rig.plans.ensurePlanFor(today).plan.priorities)
            assertEquals(1, rig.count("SELECT COUNT(*) FROM plan_priority WHERE date = ?", today.toString()))
        }
    }
}
