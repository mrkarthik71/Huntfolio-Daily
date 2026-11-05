package app.assistant.domain.engine.suggestions

import app.assistant.domain.model.Paise
import app.assistant.domain.model.PromptType
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SuggestionResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * A change a Suggestion proposes (Requirement 50.5). Accepting applies EXACTLY this and nothing else. Framework-free; the
 * data layer carries the concrete change. Plan and Bill changes are added by later phases.
 */
sealed interface ProposedChange {
    /** Set an active Subscription's amount and (optionally) interval (Requirement 38.6 price change). */
    data class SubscriptionPriceChange(val subscriptionId: Long, val newAmount: Paise, val newInterval: SubscriptionInterval) : ProposedChange

    /** Mark a Subscription ended ("cancel") after repeated non-use or a not-seen charge (Requirement 40.1). */
    data class SubscriptionCancel(val subscriptionId: Long) : ProposedChange

    /** Reactivate an ended Subscription after a charge seen post-cancellation (Requirement 40.6). */
    data class SubscriptionReactivate(val subscriptionId: Long, val nextDue: LocalDate) : ProposedChange

    /** Advance an active Subscription's next due date when the expected charge was not seen (Requirement 38.7). */
    data class SubscriptionAdvanceDue(val subscriptionId: Long, val newNextDue: LocalDate) : ProposedChange
}

/** Why a Suggestion is shown; drives its wording in the UI and its accept-rate group (Requirement 50.3). */
enum class SuggestionKind { PRICE_CHANGE, REVIEW_NON_USE, CHARGE_NOT_SEEN, CHARGE_AFTER_CANCEL }

/** A shown Suggestion the manager reasons over. [promptType] is null for rule-based ones (their own group, Requirement 50.3). */
data class SuggestionSnapshot(
    val id: Long,
    val kind: SuggestionKind,
    val promptType: PromptType?,
    val text: String,
    val reason: String,
    val shownAt: Instant,
    val response: SuggestionResponse?,
    val change: ProposedChange?,
)

/**
 * Shows Suggestions and records responses (Requirement 50). Pure: given the stored Suggestions and the clock, it decides
 * what is visible, what an accept applies, and the accept rate. A Suggestion with no response 7 days after it was shown
 * counts as ignored and stops showing (Requirement 50.4). Accept applies EXACTLY the stored change (50.5).
 */
object SuggestionManager {
    val EXPIRY: Duration = Duration.ofDays(7)

    /** Suggestions still worth showing: no response yet and not past the 7-day expiry (Requirement 50.1, 50.4). */
    fun visible(all: List<SuggestionSnapshot>, now: Instant): List<SuggestionSnapshot> =
        all.filter { it.response == null && Duration.between(it.shownAt, now) < EXPIRY }

    /** The change an accept applies (Requirement 50.5); null when the Suggestion carries no change (advice-only). */
    fun accept(suggestion: SuggestionSnapshot): ProposedChange? = suggestion.change

    /** Ids of Suggestions that have passed the 7-day expiry with no response: the caller records them IGNORED (50.4). */
    fun expired(all: List<SuggestionSnapshot>, now: Instant): List<Long> =
        all.filter { it.response == null && Duration.between(it.shownAt, now) >= EXPIRY }.map { it.id }

    /**
     * Accept rate per group for Suggestions SHOWN in the last 30 days: accepted ÷ (accepted + dismissed + ignored),
     * rounded to the nearest whole percent; null for a group with no responded Suggestion in the window (Requirement 50.3,
     * 50.6). Rule-based Suggestions (promptType null) are one group keyed by null.
     */
    fun acceptRateByGroup(all: List<SuggestionSnapshot>, now: Instant): Map<PromptType?, Int?> {
        val windowStart = now.minus(Duration.ofDays(ACCEPT_RATE_WINDOW_DAYS))
        return all
            .filter { !it.shownAt.isBefore(windowStart) && it.response != null }
            .groupBy { it.promptType }
            .mapValues { (_, group) -> acceptRate(group) }
    }

    private fun acceptRate(group: List<SuggestionSnapshot>): Int? {
        val total = group.size
        if (total == 0) return null
        val accepted = group.count { it.response == SuggestionResponse.ACCEPTED }
        return Math.round(accepted * PERCENT.toDouble() / total).toInt()
    }

    private const val ACCEPT_RATE_WINDOW_DAYS = 30L
    private const val PERCENT = 100
}
