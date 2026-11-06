package app.assistant.ui.money.reports

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import app.assistant.domain.engine.reports.MonthlyCategoryLine
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SignedPaise
import app.assistant.testing.FakeMoneyReportRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth

/** Task 23.3 / Requirement 35: a monthly report lists its categories with the change, and an empty period shows the empty state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpendingReportsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(repository: FakeMoneyReportRepository) {
        val viewModel = SpendingReportsViewModel(repository, FakeTimeSource(), app.assistant.domain.port.Advisor.Disabled)
        compose.setContent {
            AssistantTheme { SpendingReportsScreen(viewModel = viewModel) }
        }
    }

    @Test
    fun aMonthlyReport_listsItsCategoriesWithTheChangeFromTheMonthBefore() {
        val report = MonthlySpendingReport(
            month = YearMonth.of(2026, 11),
            total = Paise(150_000),
            lines = listOf(
                MonthlyCategoryLine(CategoryId(2), "Food", Paise(120_000), Paise(100_000), SignedPaise(20_000), 20),
                MonthlyCategoryLine(CategoryId(5), "Transport", Paise(30_000), Paise(0), SignedPaise(30_000), null),
            ),
            reversals = Paise.ZERO,
        )
        show(FakeMoneyReportRepository(months = mapOf(YearMonth.of(2026, 11) to report)))

        compose.onNodeWithText("Total spent").assertIsDisplayed()
        compose.onNodeWithText("₹1,500").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Food"))
        compose.onNodeWithText("Food").assertIsDisplayed()
        compose.onNodeWithText("Up ₹200 (20%) from the month before").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Transport"))
        compose.onNodeWithText("Up ₹300 from the month before").assertIsDisplayed()
    }

    @Test
    fun aPeriodWithNoSpending_showsTheEmptyState() {
        show(FakeMoneyReportRepository())

        compose.onNodeWithText("No spending was recorded for this period.").assertIsDisplayed()
        compose.onAllNodesWithText("Total spent").assertCountEquals(0)
    }
}
