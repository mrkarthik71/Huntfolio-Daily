package app.assistant.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.Paise
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.testing.FakeBillRepository
import app.assistant.testing.FakeCalendarRepository
import app.assistant.testing.FakeGoalRepository
import app.assistant.testing.FakePlanRepository
import app.assistant.testing.FakeSettingsStore
import app.assistant.testing.FakeTaskRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Task 11.5 / Requirements 13.4, 15.4: the Today_Screen shows the empty state with no Priorities,
 * shows a Priority when one exists, and Quick_Add rejects blank text (no Task created).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val time = FakeTimeSource()
    private val today: LocalDate = LocalDate.of(2026, 11, 3)

    private fun viewModel(
        tasks: FakeTaskRepository,
        priorities: List<TaskId> = emptyList(),
        billRepository: FakeBillRepository = FakeBillRepository(),
    ): TodayViewModel = TodayViewModel(
        plans = FakePlanRepository(tasks, priorities),
        tasks = tasks,
        goals = FakeGoalRepository(),
        bills = billRepository,
        time = time,
        calendar = FakeCalendarRepository(),
        settings = FakeSettingsStore(),
    )

    @Test
    fun emptyState_showsNoPrioritiesMessage() {
        val tasks = FakeTaskRepository()
        compose.setContent {
            AssistantTheme { TodayScreen(onOpenReview = {}, viewModel = viewModel(tasks)) }
        }
        compose.onNodeWithText("No priorities are set. Add a task below to plan your day.").assertIsDisplayed()
    }

    @Test
    fun withPriority_showsTheTaskTitle() {
        val task = Task(TaskId(1), "Ship the release", TaskStatus.OPEN, null, today, null, null, Instant.EPOCH, null)
        val tasks = FakeTaskRepository(listOf(task))
        compose.setContent {
            AssistantTheme { TodayScreen(onOpenReview = {}, viewModel = viewModel(tasks, listOf(TaskId(1)))) }
        }
        compose.onNodeWithText("Ship the release").assertIsDisplayed()
    }

    @Test
    fun billsDueWithinTwoDays_areListedAndLaterOrPaidOnesAreNot() {
        fun bill(id: Long, name: String, days: Long, paid: Boolean = false) =
            BillOccurrence(BillId(id), name, Paise(123_400), today.plusDays(days), BillRecurrence.NONE, 1, null, paid, id, false)
        val bills = FakeBillRepository(listOf(bill(1, "Electricity", 1), bill(2, "Rent", 5), bill(3, "Water", 0, paid = true)))
        compose.setContent {
            AssistantTheme { TodayScreen(onOpenReview = {}, viewModel = viewModel(FakeTaskRepository(), billRepository = bills)) }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Electricity", substring = true))
        compose.onNodeWithText("Electricity", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Rent", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Water", substring = true).assertCountEquals(0)
    }

    @Test
    fun quickAdd_rejectsBlankText_createsNoTask() {
        val tasks = FakeTaskRepository()
        val vm = viewModel(tasks)
        var lastResultWasError = false
        vm.quickAdd("   ") { result -> lastResultWasError = result is app.assistant.domain.error.Outcome.Err }
        // The blank quick-add must not create a Task and must report the validation error.
        assertEquals(0, tasks.tasks.value.size)
        assertEquals(true, lastResultWasError)
    }
}
