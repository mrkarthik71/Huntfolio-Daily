// Feature: personal-assistant-android, Property 19: Evening review conservation and atomicity
package app.assistant.data.repo

import android.app.Application
import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.review.ReviewSummary
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.TaskStatus
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private data class ReviewTaskSpec(val plannedOffset: Int?, val initial: TaskStatus, val choice: ReviewChoice?)

/**
 * Property 19 on the real repositories (Requirements 16.1, 16.3, 16.4, 16.6, 16.7): the listed Tasks are exactly those
 * planned for the Review_Date; the done, moved, dropped and left-open counts add up to the number listed; finishing applies
 * each choice (a move goes to the next date with one Task_Move); Tasks that are not listed, and Tasks already done or dropped,
 * never change; and a draft that is not finished writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReviewConservationPropertyTest {
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
        ReviewTaskSpec(
            Arb.element(listOf(null, -1, 0, 0, 0, 1)).bind(),
            Arb.element(listOf(TaskStatus.OPEN, TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.DROPPED)).bind(),
            Arb.element(listOf(null, ReviewChoice.DONE, ReviewChoice.MOVE_TO_TOMORROW, ReviewChoice.DROP)).bind(),
        )
    }

    @Test
    fun `Property 19 - evening review conserves tasks and finishes atomically`() {
        runBlocking {
            checkAll(150, Arb.list(specArb, 0..8)) { specs ->
                rig.reset()
                val today = rig.time.today()
                val now = rig.time.now()
                val tomorrow = today.plusDays(1)
                val ids = specs.map { spec ->
                    val input = TaskInput("t", plannedDate = spec.plannedOffset?.let { today.plusDays(it.toLong()) })
                    val id = (rig.tasks.save(input, null, now, today) as Outcome.Ok).value
                    when (spec.initial) {
                        TaskStatus.DONE -> rig.tasks.markDone(id, now, today)
                        TaskStatus.DROPPED -> rig.tasks.drop(id)
                        else -> Unit
                    }
                    id
                }
                val draft = specs.indices.fold(ReviewDraft(today)) { d, i -> d.choose(ids[i], specs[i].choice) }
                val before = ids.map { checkNotNull(rig.tasks.get(it)) }

                // Building a draft writes nothing (Requirement 16.7).
                assertEquals(before, ids.map { checkNotNull(rig.tasks.get(it)) })
                assertEquals(0, rig.count("SELECT COUNT(*) FROM task_move"))

                val listedIds = before.filter { it.plannedDate == today }.map { it.id }.toSet()
                assertEquals(listedIds, rig.tasks.plannedOn(today).first().map { it.id }.toSet())

                var done = 0
                var moved = 0
                var dropped = 0
                var open = 0
                for (i in ids.indices) {
                    if (ids[i] !in listedIds) continue
                    when (before[i].status) {
                        TaskStatus.DONE -> done++
                        TaskStatus.DROPPED -> dropped++
                        else -> when (specs[i].choice) {
                            ReviewChoice.DONE -> done++
                            ReviewChoice.MOVE_TO_TOMORROW -> moved++
                            ReviewChoice.DROP -> dropped++
                            null -> open++
                        }
                    }
                }

                val summary = rig.reviews.finish(draft, now, today)

                assertEquals(ReviewSummary(done, moved, dropped, open), summary)
                assertEquals(listedIds.size, summary.total)
                assertEquals(moved, rig.count("SELECT COUNT(*) FROM task_move"))
                for (i in ids.indices) {
                    val after = checkNotNull(rig.tasks.get(ids[i]))
                    val changes = ids[i] in listedIds && before[i].status == TaskStatus.OPEN && specs[i].choice != null
                    if (!changes) {
                        assertEquals(before[i], after)
                        continue
                    }
                    when (specs[i].choice) {
                        ReviewChoice.DONE -> assertEquals(TaskStatus.DONE, after.status)
                        ReviewChoice.DROP -> assertEquals(TaskStatus.DROPPED, after.status)
                        else -> {
                            assertEquals(TaskStatus.OPEN, after.status)
                            assertEquals(tomorrow, after.plannedDate)
                            assertEquals(1, rig.count("SELECT COUNT(*) FROM task_move WHERE task_id = ?", ids[i].value))
                        }
                    }
                }
            }
        }
    }
}
