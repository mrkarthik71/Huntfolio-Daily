package app.assistant.ui.money.budgets

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.testing.FakeMoneyBudgetRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 23.3 / Requirement 33: an over-budget category says so in words, and an invalid Budget is rejected with errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BudgetsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val food = CategoryId(2)
    private val groceries = CategoryId(3)

    private fun show(repository: FakeMoneyBudgetRepository) {
        val viewModel = BudgetsViewModel(repository, FakeTimeSource())
        compose.setContent {
            AssistantTheme { BudgetsScreen(viewModel = viewModel) }
        }
    }

    @Test
    fun overBudgetText_isShownForACategoryOverItsBudget() {
        val repository = FakeMoneyBudgetRepository(
            initial = mapOf(food to Paise(500_000), groceries to Paise(500_000)),
            spent = mapOf(food to Paise(650_000), groceries to Paise(100_000)),
        )
        show(repository)

        compose.onNodeWithText("Over budget by ₹1,500").assertIsDisplayed()
        compose.onNodeWithText("130% used").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("₹4,000 left"))
        compose.onNodeWithText("₹4,000 left").assertIsDisplayed()
    }

    @Test
    fun anInvalidBudget_isRejectedAndShowsFieldErrors() {
        val repository = FakeMoneyBudgetRepository()
        show(repository)

        compose.onNodeWithText("Add a budget").performClick()
        compose.onNodeWithText("Save").performClick()

        compose.onAllNodesWithText("This is required")[0].assertIsDisplayed()
        assertTrue(repository.amounts.isEmpty())
    }
}
