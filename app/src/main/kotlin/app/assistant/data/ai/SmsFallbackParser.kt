package app.assistant.data.ai

import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.port.Advisor
import app.assistant.domain.port.MessageFacts
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.SmsAdvice
import app.assistant.domain.port.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SMS fallback parser (Requirement 49, task 30.3). It is OFF by default and runs at most ONCE
 * per Review_Queue item: it checks `fallbackAttempted` first and marks the item attempted whatever
 * the outcome, so a failure is never retried (Requirement 49.4). The amount guard — the returned
 * amount must appear verbatim in the redacted text — is enforced by the Output_Validator, so an
 * output naming an absent figure never becomes a proposal (Requirement 49.3). On success the
 * proposal is stored for the user to confirm; nothing is applied automatically (Requirement 49.2,
 * 30.5).
 */
@Singleton
class SmsFallbackParser @Inject constructor(
    private val settings: SettingsStore,
    private val review: ReviewQueueRepository,
    private val advisor: Advisor,
) {
    /**
     * Attempts the fallback parse for one new review item. Returns true when a proposal was stored,
     * false otherwise (disabled, already attempted, or no usable output). Always leaves the item
     * marked attempted so it is never tried again.
     */
    suspend fun attempt(id: ReviewItemId): Boolean {
        if (!settings.current().smsFallbackParserEnabled) return false
        val item = review.get(id) ?: return false
        if (item.fallbackAttempted) return false

        val facts = MessageFacts(handle = "m1", rawText = item.redactedText)
        return when (val r = advisor.parseSms(facts)) {
            is SmsAdvice.Proposal -> {
                // Store the validated proposal (amount already proven to be in the redacted text).
                review.markFallbackAttempted(id, proposalJson = r.json)
                true
            }
            SmsAdvice.Unusable, is SmsAdvice.Unavailable -> {
                review.markFallbackAttempted(id, proposalJson = null)
                false
            }
        }
    }
}
