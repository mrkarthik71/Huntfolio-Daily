package app.assistant.ui.money.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.testing.FakeMoneyTransactions
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.fakeOffer
import app.assistant.testing.fakeTxn
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Task 23.1 / 23.3, Requirements 31.4, 32: the Transactions tab shows the list, rejects merging a debit with a credit,
 * keeps a Transaction unchanged after an invalid edit, and asks before deleting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(repo: FakeMoneyTransactions) {
        val viewModel = TransactionsViewModel(repo, FakeTimeSource())
        compose.setContent { AssistantTheme { TransactionsScreen(viewModel = viewModel) } }
    }

    @Test
    fun list_showsMerchantAmountAndCategory() {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, amountPaise = 123_450, merchant = "Swiggy", category = CategoryId(2))))
        show(repo)

        compose.onNodeWithText("Swiggy").assertIsDisplayed()
        compose.onNodeWithText("₹1,234.50").assertIsDisplayed()
        compose.onNode(hasText("Food", substring = true)).assertIsDisplayed()
    }

    @Test
    fun mergeOfADebitAndACredit_isRejected() {
        val repo = FakeMoneyTransactions(
            listOf(
                fakeTxn(1, TxnType.DEBIT, amountPaise = 50_000, merchant = "Swiggy", at = Instant.parse("2026-11-02T06:30:00Z")),
                fakeTxn(2, TxnType.CREDIT, amountPaise = 70_000, merchant = "Refund", at = Instant.parse("2026-11-01T06:30:00Z")),
            ),
        )
        show(repo)

        compose.onNodeWithText("Merge two").performClick()
        compose.onNodeWithText("Swiggy").performClick()
        compose.onNodeWithText("Refund").performClick()
        compose.onNodeWithText("Merge selected").performClick()

        compose.onNodeWithText("A debit and a credit cannot be merged. Nothing was changed.").assertIsDisplayed()
        compose.onNodeWithText("Swiggy").assertIsDisplayed()
        compose.onNodeWithText("Refund").assertIsDisplayed()
        assertTrue(repo.merges.isEmpty())
        assertEquals(2, repo.current.size)
    }

    @Test
    fun anInvalidEdit_keepsTheTransactionUnchangedAndShowsTheFieldError() {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, amountPaise = 50_000, merchant = "Swiggy")))
        show(repo)

        compose.onNodeWithText("Swiggy").performClick()
        compose.onNode(hasSetTextAction() and hasText("Amount (₹)")).performTextReplacement("0")
        compose.onNodeWithText("Save").performScrollTo().performClick()

        compose.onNodeWithText("Outside the allowed range").assertExists()
        assertTrue(repo.edits.isEmpty())
        assertEquals(50_000L, repo.current.single().amount.value)
    }

    @Test
    fun delete_asksForConfirmationFirst() {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, merchant = "Swiggy")))
        show(repo)

        compose.onNodeWithContentDescription("More actions for Swiggy").performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Delete this transaction?").assertIsDisplayed()
        compose.onNodeWithText("The messages it came from will not create it again.").assertIsDisplayed()
        assertTrue(repo.deleted.isEmpty())

        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf(TransactionId(1)), repo.deleted)
        compose.onNodeWithText("Transaction deleted").assertIsDisplayed()
    }

    @Test
    fun categoryChange_offersToApplyToEarlierAndReportsTheCount() {
        val food = CategoryId(2)
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, merchant = "Swiggy"))).apply {
            offerToReturn = fakeOffer(3, food, TransactionId(1))
            appliedCount = 3
        }
        show(repo)

        compose.onNodeWithContentDescription("More actions for Swiggy").performClick()
        compose.onNodeWithText("Change category").performClick()
        compose.onNodeWithText("Food").performClick()

        compose.onNodeWithText("Apply to earlier transactions?").assertIsDisplayed()
        compose.onNodeWithText("Apply to earlier").performClick()

        compose.onNodeWithText("3 earlier transactions updated").assertIsDisplayed()
        assertEquals(1, repo.appliedOffers.size)
        assertEquals(0, compose.onAllNodesWithText("Apply to earlier transactions?").fetchSemanticsNodes().size)
    }
}
