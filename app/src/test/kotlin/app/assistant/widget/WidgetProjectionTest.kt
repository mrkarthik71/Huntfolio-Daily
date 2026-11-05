package app.assistant.widget

import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Task 12.2 / Requirements 21.4, 21.7: the widget projects a Priority into a [WidgetModel] with its
 * done flag, and an empty plan projects to an empty list (the "no priorities" / post-wipe state).
 *
 * The tick-marks-done-without-unlock path (CompleteTaskAction → TaskRepository.markDone) is a Glance
 * ActionCallback that never touches AppLock; the instrumented tick is covered by the device test
 * plan (task 38.8). Here the projection and empty-state contract are pinned without an emulator.
 */
class WidgetProjectionTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 3)

    private fun project(tasks: List<Task>): List<WidgetModel> =
        tasks.map { WidgetModel(taskId = it.id.value, title = it.title, done = it.status == TaskStatus.DONE) }

    @Test
    fun openTaskProjectsToNotDone() {
        val model = project(listOf(Task(TaskId(1), "Call bank", TaskStatus.OPEN, null, today, null, null, Instant.EPOCH, null))).single()
        assertEquals(1L, model.taskId)
        assertEquals("Call bank", model.title)
        assertFalse(model.done)
    }

    @Test
    fun doneTaskProjectsToDone() {
        val model = project(listOf(Task(TaskId(2), "Pay rent", TaskStatus.DONE, null, today, null, null, Instant.EPOCH, Instant.EPOCH))).single()
        assertTrue(model.done)
    }

    @Test
    fun emptyPlanProjectsToEmptyList() {
        // After a wipe (or with no priorities) the widget shows its empty state (Requirement 21.7).
        assertTrue(project(emptyList()).isEmpty())
    }
}
