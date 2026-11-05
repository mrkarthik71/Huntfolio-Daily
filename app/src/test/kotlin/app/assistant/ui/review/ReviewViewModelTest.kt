package app.assistant.ui.review

import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.testing.FakeReviewRepository
import app.assistant.testing.FakeTaskRepository
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Task 11.5 / Requirement 16.7: leaving the Evening_Review without tapping Finish persists nothing.
 * Choices live only in the draft; `ReviewRepository.finish` is the single write, and it is never
 * called unless the User finishes.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewViewModelTest {

    private val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun leavingWithoutFinishing_writesNothing() {
        val date = LocalDate.of(2026, 11, 3)
        val task = Task(TaskId(1), "Review PR", TaskStatus.OPEN, null, date, null, null, Instant.EPOCH, null)
        val reviews = FakeReviewRepository()
        val vm = ReviewViewModel(
            tasks = FakeTaskRepository(listOf(task)),
            reviews = reviews,
            time = FakeTimeSource(),
        )
        vm.setDate(date)
        // Make a choice but never call finish() — the User navigates away.
        vm.choose(TaskId(1), ReviewChoice.DONE)

        assertFalse("finish() must not run until the User taps Finish", reviews.finishCalled)
    }
}
