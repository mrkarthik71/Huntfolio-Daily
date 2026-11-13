package app.assistant.data.repo

import app.assistant.domain.engine.tasks.RepeatRule
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.engine.tasks.TaskRepeat
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepeatingTaskTest {
    private val rig = Phase1Rig()
    private val today = LocalDate.of(2026, 11, 13)

    @After
    fun tearDown() = rig.close()

    private fun add(title: String, planned: LocalDate): TaskId = runBlocking {
        (rig.tasks.save(TaskInput(title, plannedDate = planned, estimateText = "20"), null, rig.time.now(), today) as Outcome.Ok).value
    }

    @Test
    fun doneRepeatingTaskCreatesTheNextOneAndMovesTheRule() = runBlocking {
        val id = add("Pay rent", LocalDate.of(2026, 11, 5))
        rig.tasks.setRepeat(id, TaskRepeat(RepeatRule.MONTHLY))

        rig.tasks.markDone(id, rig.time.now(), today)

        val open = rig.tasks.openTasks().first().filter { it.title == "Pay rent" && it.status == TaskStatus.OPEN }
        assertEquals(1, open.size)
        assertEquals(LocalDate.of(2026, 12, 5), open.single().plannedDate)
        assertEquals(20, open.single().estimateMinutes)
        val repeats = rig.tasks.repeats().first()
        assertNull(repeats[id])
        assertEquals(RepeatRule.MONTHLY, repeats[open.single().id]?.rule)
    }

    @Test
    fun aTaskWithoutRepeatStaysDone() = runBlocking {
        val id = add("One-off", today)
        rig.tasks.markDone(id, rig.time.now(), today)
        assertEquals(0, rig.tasks.openTasks().first().count { it.title == "One-off" })
    }

    @Test
    fun clearingTheRepeatStopsIt() = runBlocking {
        val id = add("Water plants", today)
        rig.tasks.setRepeat(id, TaskRepeat(RepeatRule.EVERY_N_DAYS, 3))
        rig.tasks.setRepeat(id, null)
        rig.tasks.markDone(id, rig.time.now(), today)
        assertEquals(0, rig.tasks.openTasks().first().count { it.title == "Water plants" })
    }
}
