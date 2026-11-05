package app.assistant.ui.subscriptions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SubscriptionStatus
import app.assistant.domain.port.DueUsageCheck
import app.assistant.testing.FakeSubscriptionRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Task 26.6 / Requirements 37.1, 37.7, 38.1, 38.3, 39.4: the Subscriptions section shows ₹0 with
 * nothing active, moves a confirmed proposal to Active, answers a usage check with one tap, and
 * rejects a manual Subscription with an empty merchant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today: LocalDate = LocalDate.of(2026, 11, 3)

    private fun show(repository: FakeSubscriptionRepository) {
        val vm = SubscriptionsViewModel(repository, FakeTimeSource())
        compose.setContent { AssistantTheme { SubscriptionsScreen(viewModel = vm) } }
    }

    @Test
    fun withNothingActive_totalIsZero_andEmptyStatesShow() {
        show(FakeSubscriptionRepository(today))
        compose.onNodeWithText("₹0").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("No active subscriptions yet."))
        compose.onNodeWithText("No active subscriptions yet.").assertIsDisplayed()
    }

    @Test
    fun confirmingAProposal_movesItToActive() {
        val proposal = FakeSubscriptionRepository.view(7, "StreamFlix", Paise(14_900), SubscriptionInterval.MONTHLY, today.plusDays(4), today, SubscriptionStatus.PROPOSED)
        val repository = FakeSubscriptionRepository(today, proposals = listOf(proposal))
        show(repository)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Confirm"))
        compose.onNodeWithText("Confirm").performClick()
        compose.waitForIdle()

        assertEquals(0, repository.proposalsState.value.size)
        assertEquals(listOf(7L), repository.activeState.value.map { it.snapshot.id })
        // ₹149 a month is ₹1,788 a year in the hero total.
        compose.onAllNodesWithText("₹1,788", substring = true).fetchSemanticsNodes().isNotEmpty().let { assertEquals(true, it) }
    }

    @Test
    fun usageCheck_isAnsweredWithOneTap() {
        val repository = FakeSubscriptionRepository(today, usage = listOf(DueUsageCheck(3, "MusicBox", Paise(9_900))))
        show(repository)

        compose.onNodeWithText("Yes, I used it").performClick()
        compose.waitForIdle()

        assertEquals(listOf(3L to true), repository.usageAnswers)
    }

    @Test
    fun manualAdd_withEmptyMerchant_isRejectedAndNothingIsSaved() {
        val repository = FakeSubscriptionRepository(today)
        show(repository)

        compose.onNodeWithText("Add a subscription").performClick()
        compose.onNodeWithText("Amount (₹)").performTextInput("199")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Save"))
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Enter the merchant name.").assertExists()
        assertEquals(0, repository.activeState.value.size)
    }
}
