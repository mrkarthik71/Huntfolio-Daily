package app.assistant.domain.engine.notify

import app.assistant.domain.model.NotificationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class GoalNudgeLimitTest {
    private val now = Instant.parse("2026-11-03T05:00:00Z")
    private val zone = ZoneId.of("Asia/Kolkata")
    private val nudge = Candidate(NotificationType.GOAL_NUDGE, "daily:2026-11-03", now)

    private fun settings() = GovernorSettings(
        enabled = setOf(NotificationType.GOAL_NUDGE),
        quietHours = null,
        dailyCap = 8,
        perTypeLimit = mapOf(NotificationType.GOAL_NUDGE to BriefContent.GOAL_NUDGE_DAILY_LIMIT),
        capExempt = emptySet(),
    )

    @Test
    fun `the first nudge of the day is shown`() {
        val state = GovernorState(now.atZone(zone).toLocalDate(), 0, emptyMap())
        assertEquals(Decision.Show, NotificationPolicy.decide(nudge, state, settings(), now, zone))
    }

    @Test
    fun `a second nudge on the same day goes to the inbox`() {
        val day = now.atZone(zone).toLocalDate()
        val state = GovernorState(day, 1, mapOf(NotificationType.GOAL_NUDGE to 1))
        assertEquals(Decision.ToInbox(InboxReason.PER_TYPE_LIMIT), NotificationPolicy.decide(nudge, state, settings(), now, zone))
    }
}
