package app.assistant.domain.engine.plan

import app.assistant.domain.model.GoalId
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimedEvent
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Properties 15, 16 and 17 (Requirements 14.2 to 14.5, 14.10, 23.2, 23.3). */
class PlanBuilderPropertyTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 3)
    private val hours = LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))
    private val goals = (1L..4L).map(::GoalId)

    private val taskArb = arbitrary { rs ->
        val id = Arb.int(1..10_000).bind()
        Task(
            id = TaskId(id.toLong()),
            title = "t$id",
            status = Arb.element(TaskStatus.OPEN, TaskStatus.OPEN, TaskStatus.OPEN, TaskStatus.DONE, TaskStatus.DROPPED).bind(),
            dueDate = Arb.int(-5..5).orNull(0.3).bind()?.let { today.plusDays(it.toLong()) },
            plannedDate = Arb.int(-3..3).orNull(0.4).bind()?.let { today.plusDays(it.toLong()) },
            estimateMinutes = Arb.int(5..240).orNull(0.3).bind(),
            goalId = Arb.element(goals).orNull(0.5).bind(),
            createdAt = Instant.parse("2026-10-01T00:00:00Z").plusSeconds(Arb.int(0..1_000_000).bind().toLong()),
            completedAt = null,
        ).also { rs.random }
    }

    private val eventArb = arbitrary {
        val startMin = Arb.int(-120..(24 * 60)).bind()
        val len = Arb.int(0..240).bind()
        val s = today.atStartOfDay(zone).toInstant().plus(Duration.ofMinutes(startMin.toLong()))
        TimedEvent(InstantRange(s, s.plus(Duration.ofMinutes(len.toLong()))), "e")
    }

    private fun inputs(tasks: List<Task>, events: List<TimedEvent>, createdMin: Int, behind: Set<GoalId>) = PlanInputs(
        date = today,
        createdAt = today.atStartOfDay(zone).toInstant().plus(Duration.ofMinutes(createdMin.toLong())),
        zone = zone,
        openTasks = tasks.distinctBy { it.id },
        behindGoalIds = behind,
        timedEvents = events,
        workingHours = hours,
    )

    @Test
    fun `priorities are the top three candidates of the reference ranking`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.list(taskArb, 0..15), Arb.list(Arb.element(goals), 0..3)) { tasks, behindList ->
            val behind = behindList.toSet()
            val unique = tasks.distinctBy { it.id }
            val plan = PlanBuilder.build(inputs(unique, emptyList(), 0, behind))
            val reference = unique
                .filter { it.status == TaskStatus.OPEN && (it.plannedDate == null || !it.plannedDate!!.isAfter(today)) }
                .sortedWith(
                    Comparator { a, b ->
                        fun overdue(t: Task) = t.dueDate?.isBefore(today) == true
                        fun linked(t: Task) = t.goalId != null && t.goalId in behind
                        when {
                            overdue(a) != overdue(b) -> if (overdue(a)) -1 else 1
                            a.dueDate != b.dueDate -> when {
                                a.dueDate == null -> 1
                                b.dueDate == null -> -1
                                else -> a.dueDate!!.compareTo(b.dueDate)
                            }
                            linked(a) != linked(b) -> if (linked(a)) -1 else 1
                            a.createdAt != b.createdAt -> a.createdAt.compareTo(b.createdAt)
                            else -> a.id.value.compareTo(b.id.value)
                        }
                    },
                )
                .take(3)
                .map { it.id }
            assertEquals(reference, plan.priorities)
        }
    }

    @Test
    fun `time blocks fit working hours, avoid events and each other`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.list(taskArb, 0..15), Arb.list(eventArb, 0..8), Arb.int(0..(24 * 60))) { tasks, events, createdMin ->
            val inp = inputs(tasks, events, createdMin, emptySet())
            val plan = PlanBuilder.build(inp)
            val window = PlanBuilder.workingWindow(today, zone, hours, inp.createdAt)
            val byId = inp.openTasks.associateBy { it.id }
            for (b in plan.timeBlocks) {
                assertTrue(b.range.start < b.range.end)
                assertTrue(window != null && window.contains(b.range))
                assertTrue(b.taskId in plan.priorities)
                assertEquals(Duration.ofMinutes(byId.getValue(b.taskId!!).estimateMinutes!!.toLong()), b.range.duration)
                for (e in events) assertFalse(b.range.overlaps(e.range))
            }
            for ((i, a) in plan.timeBlocks.withIndex()) {
                for (b in plan.timeBlocks.drop(i + 1)) assertFalse(a.range.overlaps(b.range))
            }
        }
    }

    @Test
    fun `building twice gives the same plan whatever the input order`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.list(taskArb, 0..15), Arb.list(eventArb, 0..8), Arb.int(0..(24 * 60))) { raw, events, createdMin ->
            // Task IDs are unique in real data; the generator can repeat an ID with different contents.
            val tasks = raw.distinctBy { it.id }
            val a = PlanBuilder.build(inputs(tasks, events, createdMin, setOf(goals[0])))
            val b = PlanBuilder.build(inputs(tasks.reversed(), events.reversed(), createdMin, setOf(goals[0])))
            assertEquals(a, b)
        }
    }

    @Test
    fun `user priority order survives a rebuild`() {
        val tasks = (1L..5L).map { Task(TaskId(it), "t$it", TaskStatus.OPEN, null, today, 30, null, Instant.EPOCH.plusSeconds(it), null) }
        val override = PlanOverride.SetPriorities(listOf(TaskId(5), TaskId(2)))
        val plan = PlanBuilder.build(inputs(tasks, emptyList(), 0, emptySet()), override)
        assertEquals(listOf(TaskId(5), TaskId(2)), plan.priorities)
    }
}
