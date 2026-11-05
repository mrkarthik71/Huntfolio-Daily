package app.assistant.ui.why

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.LogDecision
import app.assistant.domain.port.LogEntry
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Task 11.5 / Requirement 18.7: the "Why am I seeing this?" screen can switch the notification type
 * off. Tapping "Turn this reminder off" calls back with the entry's type so the caller removes it
 * from the enabled set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhyScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun turnOff_firesCallbackWithEntryType() {
        val entry = LogEntry(
            id = 1,
            day = "2026-11-03",
            type = NotificationType.GOAL_NUDGE,
            refKey = "g:1",
            decision = LogDecision.SHOWN,
            ruleId = "daily.goal_nudge",
            at = Instant.EPOCH,
            counted = true,
            typeCounted = true,
            summary = "Goal nudge",
        )
        var turnedOff: NotificationType? = null
        compose.setContent {
            AssistantTheme {
                WhyScreen(entry = entry, loaded = true, onTurnOff = { turnedOff = it }, onClose = {})
            }
        }

        compose.onNodeWithText("Turn this reminder off").performClick()
        assertEquals(NotificationType.GOAL_NUDGE, turnedOff)
    }
}
