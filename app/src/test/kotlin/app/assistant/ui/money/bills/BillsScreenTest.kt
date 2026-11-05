package app.assistant.ui.money.bills

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.BillPaidProposal
import app.assistant.testing.FakeMoneyBillRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Task 23.3 / Requirement 34: the Bills tab rejects an invalid form and resolves "did this Transaction pay this Bill?" suggestions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val due = LocalDate.of(2026, 11, 10)

    private fun bill(id: Long, name: String) =
        BillOccurrence(BillId(id), name, Paise(123_400), due, BillRecurrence.NONE, due.dayOfMonth, null, false, id, false)

    private fun show(repository: FakeMoneyBillRepository) {
        val viewModel = BillsViewModel(repository, FakeTimeSource())
        compose.setContent {
            AssistantTheme { BillsScreen(viewModel = viewModel) }
        }
    }

    @Test
    fun anInvalidBillForm_isRejectedAndShowsFieldErrors() {
        val repository = FakeMoneyBillRepository()
        show(repository)

        compose.onNodeWithText("Add a bill").performClick()
        compose.onNodeWithText("Save").performClick()

        compose.onAllNodesWithText("This is required")[0].assertIsDisplayed()
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun aPaidProposal_canBeAcceptedAndDismissed() {
        val repository = FakeMoneyBillRepository(
            initial = listOf(bill(1, "Electricity"), bill(2, "Water")),
            paidProposals = listOf(BillPaidProposal(TransactionId(10), BillId(1)), BillPaidProposal(TransactionId(11), BillId(2))),
        )
        show(repository)

        compose.onAllNodesWithText("Yes, mark paid")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(TransactionId(10) to BillId(1)), repository.acceptedPaid)
        compose.onNodeWithText("Electricity is marked paid.").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("No, it was not"))
        compose.onNodeWithText("No, it was not").performClick()
        compose.waitForIdle()
        assertEquals(listOf(TransactionId(11) to BillId(2)), repository.dismissedPaid)
    }
}
