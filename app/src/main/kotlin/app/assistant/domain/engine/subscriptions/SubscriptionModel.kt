package app.assistant.domain.engine.subscriptions

import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SubscriptionStatus
import app.assistant.domain.model.TransactionId
import java.time.LocalDate

/**
 * A debit charge the detector reasons over: the Merchant it was paid to, the amount, and the date (Received_Time's local
 * date). Framework-free so the detector is a pure function; the data layer maps stored Transactions to these.
 */
data class Charge(val transactionId: TransactionId, val merchantId: MerchantId, val amount: Paise, val date: LocalDate)

/**
 * A Subscription as the detector reasons over it (Glossary: Subscription). The data layer persists it as a Room entity;
 * this is the pure snapshot. [anchorDay] drives the Due_Date_Rule; [nextDue] is the next expected charge date.
 */
data class SubscriptionSnapshot(
    val id: Long?,
    val merchantId: MerchantId,
    val amount: Paise,
    val interval: SubscriptionInterval,
    val anchorDay: Int,
    val nextDue: LocalDate,
    val activatedOn: LocalDate,
    val lastUsageCheck: LocalDate?,
    val cancelledOn: LocalDate?,
    val status: SubscriptionStatus,
)

/** A proposed Subscription the detector found in the charges (Requirement 37.1). The User confirms or rejects it. */
data class DetectionProposal(
    val merchantId: MerchantId,
    val amount: Paise,
    val interval: SubscriptionInterval,
    val anchorDay: Int,
    val matchingCount: Int,
    val nextDue: LocalDate,
    /** The transaction ids in the Recurring_Pattern, oldest first; the first is the Anchor_Day source. */
    val chargeIds: List<TransactionId>,
)

/** How one new debit lines up against the active Subscriptions (Requirement 38.4-38.7). */
sealed interface ChargeMatch {
    /** The charge advances this Subscription's next due date by one interval. */
    data class Advance(val subscriptionId: Long, val newNextDue: LocalDate) : ChargeMatch

    /** The amount is out of tolerance: a price-change Suggestion is raised; the Subscription is unchanged. */
    data class PriceChange(val subscriptionId: Long, val oldAmount: Paise, val newAmount: Paise, val chargeDate: LocalDate) : ChargeMatch

    /** The charge matched no active Subscription. */
    data object None : ChargeMatch
}
