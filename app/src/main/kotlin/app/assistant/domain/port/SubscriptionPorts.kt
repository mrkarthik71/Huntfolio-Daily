package app.assistant.domain.port

import app.assistant.domain.engine.subscriptions.SubscriptionSnapshot
import app.assistant.domain.engine.suggestions.ProposedChange
import app.assistant.domain.engine.suggestions.SuggestionSnapshot
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** A due Usage_Check shown on the Subscriptions screen (Requirement 39.4). */
data class DueUsageCheck(val subscriptionId: Long, val merchant: String, val amount: Paise)

/** A Subscription with its Merchant name resolved, for display (Requirement 38.1). */
data class SubscriptionView(val snapshot: SubscriptionSnapshot, val merchant: String, val yearlyCost: Paise)

/** Fields for a manual Subscription the User types (Requirement 37.4). */
data class ManualSubscriptionInput(val merchant: String, val amount: Paise, val interval: SubscriptionInterval, val nextDue: LocalDate)

enum class ManualSubscriptionField { MERCHANT, AMOUNT, INTERVAL, NEXT_DUE }

/** The manual-save checks (Requirement 37.7): non-empty merchant, amount > 0 paise, next due today or later. */
object ManualSubscriptionValidator {
    private const val MAX_MERCHANT = 100

    fun validate(input: ManualSubscriptionInput, today: LocalDate): FieldErrors<ManualSubscriptionField> = fieldErrors {
        val merchant = input.merchant.trim()
        check(ManualSubscriptionField.MERCHANT, merchant.isNotEmpty(), ValidationError.EMPTY)
        check(ManualSubscriptionField.MERCHANT, merchant.length <= MAX_MERCHANT, ValidationError.TOO_LONG)
        check(ManualSubscriptionField.AMOUNT, input.amount.value > 0, ValidationError.OUT_OF_RANGE)
        check(ManualSubscriptionField.NEXT_DUE, !input.nextDue.isBefore(today), ValidationError.IN_PAST)
    }
}

/**
 * Stores Subscriptions and their lifecycle (Requirements 37-41, 50). The detector runs inside the repository as a
 * post-commit hook and whenever a Transaction is edited, merged or deleted. Confirm/reject/manual-save and the
 * usage-check, renewal and suggestion flows go through here; nothing is applied until the User acts (Requirement 2.4).
 */
interface SubscriptionRepository {
    /** Proposed Subscriptions awaiting the User (Requirement 37.1). */
    fun proposals(): Flow<List<SubscriptionView>>

    /** Active Subscriptions ordered by next due date (Requirement 38.1). */
    fun active(): Flow<List<SubscriptionView>>

    /** Total yearly cost of active Subscriptions; ₹0 when none (Requirement 38.3). */
    fun totalYearlyCost(): Flow<Paise>

    /** Suggestions still worth showing (Requirement 50.1). */
    fun suggestions(): Flow<List<SuggestionSnapshot>>

    /** Usage checks due now (Requirement 39.4). */
    fun dueUsageChecks(): Flow<List<DueUsageCheck>>

    /** Re-run detection over all debit Transactions and refresh proposals (Requirement 37.1); idempotent (37.5). */
    suspend fun redetect()

    suspend fun confirmProposal(subscriptionId: Long)

    suspend fun rejectProposal(subscriptionId: Long)

    /** Save a manual Subscription, or return field errors (Requirement 37.4, 37.7). */
    suspend fun addManual(input: ManualSubscriptionInput): Outcome<Unit, FieldErrors<ManualSubscriptionField>>

    /** Record a Usage_Check answer and raise the review Suggestion when two consecutive "No" (Requirement 39.2, 40.1). */
    suspend fun answerUsageCheck(subscriptionId: Long, usedIt: Boolean)

    /** Mark a Subscription cancelled; sends no external request (Requirement 40.3). */
    suspend fun markCancelled(subscriptionId: Long)

    suspend fun acceptSuggestion(suggestionId: Long)

    suspend fun dismissSuggestion(suggestionId: Long)

    /** Apply the accepted change exactly (Requirement 50.5); used by acceptSuggestion. Exposed for testing. */
    suspend fun applyChange(change: ProposedChange)

    /**
     * The daily subscription pass for `maintenance-daily` (Requirement 38.7, 39.1): advance any active Subscription whose
     * expected charge was not seen within Charge_Window and raise a "not seen" Suggestion, and mark a Usage_Check due for
     * any active Subscription that is due. Idempotent within a day.
     */
    suspend fun runDailyMaintenance()
}
