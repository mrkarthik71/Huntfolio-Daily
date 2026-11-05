package app.assistant.ui.money.reviewqueue

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TxnType
import app.assistant.testing.FakeReviewQueueRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.reviewItem
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 23.3 / Requirements 30.3, 30.4, 30.6: a Review_Queue save with an invalid field keeps the item and shows the error,
 * a valid save turns it into a Transaction, and dismiss removes it. The tall screen keeps the whole item composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class ReviewQueueScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val text = "Txn of Rs 250 at CAFE on card XX1234"

    private fun show(repo: FakeReviewQueueRepository) {
        val viewModel = ReviewQueueViewModel(repo, FakeTimeSource())
        compose.setContent {
            AssistantTheme { ReviewQueueScreen(viewModel = viewModel) }
        }
    }

    @Test
    fun saveWithAnInvalidField_keepsTheItemAndShowsTheError() {
        val repo = FakeReviewQueueRepository(listOf(reviewItem(1, text)))
        show(repo)

        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Amount (₹)").performTextInput("abc")
        compose.onNodeWithText("Save transaction").performClick()

        compose.onNodeWithText("Enter a number, for example 12 or 12.5").assertIsDisplayed()
        compose.onAllNodesWithText("This is required")[0].assertIsDisplayed()
        compose.onNodeWithText("Some fields need fixing. The message stays in the list.").assertIsDisplayed()
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText("1 to review").assertIsDisplayed()
        assertEquals(0, repo.resolved.size)
    }

    @Test
    fun saveWithValidFields_turnsTheItemIntoATransaction() {
        val repo = FakeReviewQueueRepository(listOf(reviewItem(1, text)))
        show(repo)

        compose.onNodeWithText("Enter details").performClick()
        compose.onNodeWithText("Debit").performClick()
        compose.onNodeWithText("Amount (₹)").performTextInput("250")
        compose.onNodeWithText("Merchant").performTextInput("Cafe")
        compose.onNodeWithText("Save transaction").performClick()

        compose.waitForIdle()
        val input = repo.resolved.single().second
        assertEquals(TxnType.DEBIT, input.type)
        assertEquals(Paise(25_000), input.amount)
        assertEquals("Cafe", input.merchant)
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun dismiss_removesTheItem() {
        val repo = FakeReviewQueueRepository(listOf(reviewItem(1, text)))
        show(repo)

        compose.onNodeWithText("Dismiss").performClick()

        compose.waitForIdle()
        assertEquals(1, repo.dismissed.size)
        compose.onNodeWithText("Nothing to review. Messages the app cannot read will appear here.").assertIsDisplayed()
        compose.onNodeWithText("0 to review").assertIsDisplayed()
    }

    @Test
    fun itemsShowTheirOriginAndRedactedText() {
        show(FakeReviewQueueRepository(listOf(reviewItem(1, text, origin = "VM-HDFCBK"))))

        compose.onNodeWithText("From VM-HDFCBK").assertIsDisplayed()
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
