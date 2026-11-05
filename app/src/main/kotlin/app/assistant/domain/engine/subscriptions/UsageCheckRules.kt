package app.assistant.domain.engine.subscriptions

import java.time.LocalDate

/**
 * The monthly Usage_Check rules (Requirement 39, 40). Pure. A check is due 30 days after the last answer, or after the
 * Subscription became active when it has no answer yet (39.1). Two consecutive "No" answers raise a review Suggestion and
 * reset the count (40.1); a "Yes" resets it (40.2).
 */
object UsageCheckRules {
    const val USAGE_CHECK_INTERVAL_DAYS = 30L
    const val CANCEL_SUGGESTION_AFTER_CONSECUTIVE_NO = 2

    /** True when a Usage_Check is due for an active Subscription as of [today] (Requirement 39.1). */
    fun isDue(activatedOn: LocalDate, lastAnswer: LocalDate?, today: LocalDate): Boolean {
        val since = lastAnswer ?: activatedOn
        return today.toEpochDay() - since.toEpochDay() >= USAGE_CHECK_INTERVAL_DAYS
    }

    /**
     * The consecutive-"No" count after an answer, and whether it crosses the threshold to raise a review Suggestion.
     * A "Yes" resets to 0; a "No" increments; reaching 2 raises the Suggestion and resets to 0 (Requirement 40.1, 40.2).
     */
    fun afterAnswer(currentConsecutiveNo: Int, usedIt: Boolean): UsageAnswerOutcome = when {
        usedIt -> UsageAnswerOutcome(newConsecutiveNo = 0, raiseReviewSuggestion = false)
        currentConsecutiveNo + 1 >= CANCEL_SUGGESTION_AFTER_CONSECUTIVE_NO -> UsageAnswerOutcome(newConsecutiveNo = 0, raiseReviewSuggestion = true)
        else -> UsageAnswerOutcome(newConsecutiveNo = currentConsecutiveNo + 1, raiseReviewSuggestion = false)
    }
}

/** The result of recording one Usage_Check answer. */
data class UsageAnswerOutcome(val newConsecutiveNo: Int, val raiseReviewSuggestion: Boolean)
