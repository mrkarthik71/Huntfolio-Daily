package app.assistant.data.repo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.tasks.MoveError
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.SettingsStore
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Repository behaviour on an in-memory Room database (Robolectric). Covers Properties 10, 14, 18
 * and 19 at the storage level (Requirements 11.4 to 11.9, 12.3 to 12.7, 14.7, 16.3 to 16.7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepositoryTest {

    private lateinit var db: AppDatabase
    private val time = FakeTimeSource()
    private val settings = object : SettingsStore {
        val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        override suspend fun current() = state.value
        override suspend fun update(change: (AppSettings) -> AppSettings) = Outcome.Ok(change(state.value).also { state.value = it })
    }
    private lateinit var goals: RoomGoalRepository
    private lateinit var tasks: RoomTaskRepository
    private lateinit var plans: RoomPlanRepository
    private lateinit var reviews: RoomReviewRepository
    private val today: LocalDate get() = time.today()
    private val now: Instant get() = time.now()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        val provider = DbProvider { db }
        goals = RoomGoalRepository(provider, settings, time)
        tasks = RoomTaskRepository(provider, settings)
        plans = RoomPlanRepository(provider, settings, goals, time, app.assistant.testing.FakeCalendarRepository())
        reviews = RoomReviewRepository(provider, settings)
    }

    @After
    fun tearDown() = db.close()

    private fun quick(title: String): TaskId = runBlocking { (tasks.quickAdd(title, now, today) as Outcome.Ok).value }

    @Test
    fun `quick add creates an open task planned for today`() = runBlocking {
        val id = quick("  Buy milk \n today ")
        val t = tasks.get(id)!!
        // The line break becomes one space; spaces around it are kept (Requirement 13.2).
        assertEquals("Buy milk   today", t.title)
        assertEquals(today, t.plannedDate)
        assertEquals(TaskStatus.OPEN, t.status)
        assertEquals(Outcome.Err(ValidationError.EMPTY), tasks.quickAdd("   ", now, today))
        assertEquals(1, tasks.openTasks().first().size)
    }

    @Test
    fun `done task leaves open list and adds one to a tasks goal in the right week`() = runBlocking {
        val g = (goals.save(GoalInput("Ship things", GoalPeriod.WEEK, today.minusDays(30), "10", "Tasks")) as Outcome.Ok).value
        val id = (tasks.save(TaskInput("Write", plannedDate = today, goalId = g), null, now, today) as Outcome.Ok).value
        tasks.markDone(id, now, today)
        tasks.markDone(id, now, today) // already done: no second increment
        assertTrue(tasks.openTasks().first().isEmpty())
        val status = goals.statuses(today).first().single()
        assertEquals(100, status.progress.hundredths)
        // Progress for another week is separate (Property 10).
        assertEquals(0, goals.statuses(today.plusDays(7)).first().single().progress.hundredths)
    }

    @Test
    fun `logging progress validates, accumulates and archived goals accept none`() = runBlocking {
        val g = (goals.save(GoalInput("Run", GoalPeriod.MONTH, today, "100", "km")) as Outcome.Ok).value
        assertEquals(Outcome.Err(ValidationError.NOT_A_NUMBER), goals.logProgress(g, "abc", today))
        assertEquals(Outcome.Ok(Unit), goals.logProgress(g, "2.5", today))
        assertEquals(Outcome.Ok(Unit), goals.logProgress(g, "2.5", today))
        assertEquals(500, goals.statuses(today).first().single().progress.hundredths)
        goals.archive(g, now)
        assertEquals(Outcome.Err(ValidationError.NOT_ALLOWED), goals.logProgress(g, "1", today))
        assertTrue(goals.statuses(today).first().isEmpty())
        assertEquals(500L, db.goalDao().progressOf(g.value, today.withDayOfMonth(1).toString()))
    }

    @Test
    fun `goal links follow the period rule`() = runBlocking {
        val week = (goals.save(GoalInput("w", GoalPeriod.WEEK, today, "1", "u")) as Outcome.Ok).value
        val month = (goals.save(GoalInput("m", GoalPeriod.MONTH, today, "1", "u")) as Outcome.Ok).value
        val year = (goals.save(GoalInput("y", GoalPeriod.YEAR, today, "1", "u")) as Outcome.Ok).value
        assertEquals(Outcome.Ok(Unit), goals.link(week, month))
        assertTrue(goals.link(week, year) is Outcome.Err)
        assertEquals(month.value, goals.get(week)!!.parentId!!.value)
        assertEquals(Outcome.Ok(Unit), goals.link(week, null))
        assertNull(goals.get(week)!!.parentId)
    }

    @Test
    fun `move records one task move, rejects the past and leaves the old plan`() = runBlocking {
        val id = quick("Plan trip")
        plans.ensurePlanFor(today)
        assertEquals(listOf(id), plans.planFor(today).first()!!.plan.priorities)
        assertEquals(Outcome.Err(MoveError.IN_PAST), tasks.move(id, today.minusDays(1), today))
        assertEquals(Outcome.Ok(Unit), tasks.move(id, today.plusDays(2), today))
        assertEquals(today.plusDays(2), tasks.get(id)!!.plannedDate)
        assertTrue(plans.planFor(today).first()!!.plan.priorities.isEmpty())
        assertEquals(1, db.taskDao().movesBetween(today.toString(), today.toString()).single().moves)
    }

    @Test
    fun `delete removes the task and its plan rows but keeps goal progress`() = runBlocking {
        val g = (goals.save(GoalInput("G", GoalPeriod.WEEK, today.minusDays(10), "5", "tasks")) as Outcome.Ok).value
        val id = (tasks.save(TaskInput("T", plannedDate = today, estimateText = "30", goalId = g), null, now, today) as Outcome.Ok).value
        plans.ensurePlanFor(today)
        tasks.markDone(id, now, today)
        tasks.delete(id)
        assertNull(tasks.get(id))
        assertTrue(plans.planFor(today).first()!!.priorities.isEmpty())
        assertTrue(plans.timeBlocks(today).isEmpty())
        assertEquals(100, goals.statuses(today).first().single().progress.hundredths)
    }

    @Test
    fun `plan is built once and user changes survive`() = runBlocking {
        val a = quick("A")
        val b = quick("B")
        val c = quick("C")
        val first = plans.ensurePlanFor(today)
        assertEquals(listOf(a, b, c), first.plan.priorities)
        plans.setPriorities(today, listOf(c, a))
        quick("D")
        val again = plans.ensurePlanFor(today) // does not rebuild
        assertEquals(listOf(c, a), again.plan.priorities)
        plans.removePriority(today, c)
        assertEquals(listOf(a), plans.ensurePlanFor(today).plan.priorities)
    }

    @Test
    fun `time blocks are stored for priorities with an estimate`() = runBlocking {
        time.instant = today.atTime(8, 0).atZone(time.zone()).toInstant()
        val id = (tasks.save(TaskInput("Focus", plannedDate = today, estimateText = "90"), null, now, today) as Outcome.Ok).value
        val plan = plans.ensurePlanFor(today)
        val block = plan.plan.timeBlocks.single()
        assertEquals(id, block.taskId)
        assertEquals(Duration.ofMinutes(90), block.range.duration)
        assertEquals(today.atTime(9, 0).atZone(time.zone()).toInstant(), block.range.start)
    }

    @Test
    fun `finishing the review applies every choice at once and counts add up`() = runBlocking {
        val done = quick("done")
        val move = quick("move")
        val drop = quick("drop")
        val leave = quick("leave")
        val draft = ReviewDraft(today)
            .choose(done, ReviewChoice.DONE).choose(move, ReviewChoice.MOVE_TO_TOMORROW).choose(drop, ReviewChoice.DROP)
        val summary = reviews.finish(draft, now, today)
        assertEquals(listOf(1, 1, 1, 1), listOf(summary.done, summary.moved, summary.dropped, summary.leftOpen))
        assertEquals(TaskStatus.DONE, tasks.get(done)!!.status)
        assertEquals(today.plusDays(1), tasks.get(move)!!.plannedDate)
        assertEquals(TaskStatus.DROPPED, tasks.get(drop)!!.status)
        assertEquals(TaskStatus.OPEN, tasks.get(leave)!!.status)
        assertEquals(today, tasks.get(leave)!!.plannedDate)
    }

    @Test
    fun `leaving the review without finishing changes nothing`() = runBlocking {
        val id = quick("keep")
        ReviewDraft(today).choose(id, ReviewChoice.DROP) // never passed to finish
        assertEquals(TaskStatus.OPEN, tasks.get(id)!!.status)
        assertNotNull(tasks.get(id))
    }

    @Test
    fun `streak counts consecutive days with a completed priority`() = runBlocking {
        val ids = (0..2).map { quick("t$it") }
        plans.ensurePlanFor(today)
        assertEquals(0, plans.streak(today).first())
        tasks.markDone(ids[0], now, today)
        assertEquals(1, plans.streak(today).first())
    }

    @Test
    fun `week report lists week goals and tasks moved twice`() = runBlocking {
        val g = (goals.save(GoalInput("Study", GoalPeriod.WEEK, today.minusDays(3), "5", "hours")) as Outcome.Ok).value
        goals.logProgress(g, "1.5", today)
        val id = quick("slippy")
        tasks.move(id, today.plusDays(1), today)
        tasks.move(id, today.plusDays(2), today)
        val week = app.assistant.domain.engine.goals.Periods.instanceContaining(GoalPeriod.WEEK, today, java.time.DayOfWeek.SUNDAY)
        val report = RoomReportRepository(DbProvider { db }, settings, time).weeklyProgress(week)
        assertEquals(150, report.goals.single().progress.hundredths)
        assertEquals(listOf("slippy" to 2), report.slipped)
        assertTrue(GoalId(g.value).value == g.value)
    }
}
