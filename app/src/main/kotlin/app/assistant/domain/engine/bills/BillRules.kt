package app.assistant.domain.engine.bills

import app.assistant.domain.engine.subscriptions.DueDateRule
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.TxnType
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A Bill occurrence, unpaid or paid (design: Bill_Tracker). [seriesId] groups a recurring Bill's occurrences. */
data class BillOccurrence(
    val id: BillId,
    val name: String,
    val amount: Paise,
    val dueDate: LocalDate,
    val recurrence: BillRecurrence,
    /** The day of the month of the series' first occurrence; every later due date lands on it or on the month's last day. */
    val anchorDay: Int,
    val merchantId: MerchantId?,
    val paid: Boolean,
    val seriesId: Long,
    val notified: Boolean,
)

enum class BillField { NAME, AMOUNT, DUE_DATE }

/** What the User types to save or accept a Bill (Requirements 34.1, 34.2, 34.6). A null due date is a missing one. */
data class BillInput(val name: String, val amount: Paise?, val dueDate: LocalDate?, val recurrence: BillRecurrence, val merchant: String? = null)

object BillValidator {
    const val NAME_MAX = 100

    /** Requirement 34.6: a name of 1 to 100 characters, a Valid_Amount and a due date. */
    fun validate(input: BillInput): FieldErrors<BillField> = fieldErrors {
        val name = input.name.trim()
        check(BillField.NAME, name.isNotEmpty(), ValidationError.EMPTY)
        check(BillField.NAME, name.length <= NAME_MAX, ValidationError.TOO_LONG)
        when {
            input.amount == null -> add(BillField.AMOUNT, ValidationError.EMPTY)
            !input.amount.isValid -> add(BillField.AMOUNT, ValidationError.OUT_OF_RANGE)
        }
        check(BillField.DUE_DATE, input.dueDate != null, ValidationError.EMPTY)
    }
}

/** How many days either side of the due date a payment still matches the Bill (Requirement 34.3). */
const val BILL_MATCH_WINDOW_DAYS = 5L

object BillRules {
    /**
     * Requirement 34.4: the due date of the next occurrence of a recurring Bill after [current]. The day of the month
     * is that of [first], so a Bill first due on 31 January is due 28 or 29 February and then 31 March. A Bill that
     * does not recur has no next occurrence, and [current] is returned unchanged.
     */
    fun nextDue(first: LocalDate, current: LocalDate, recurrence: BillRecurrence): LocalDate = nextDueOnAnchor(first.dayOfMonth, current, recurrence)

    fun nextDueOnAnchor(anchorDay: Int, current: LocalDate, recurrence: BillRecurrence): LocalDate {
        val interval = recurrence.toInterval() ?: return current
        return DueDateRule.next(current, interval, anchorDay)
    }

    /**
     * The unpaid Bill a debit Transaction pays, or null (Requirement 34.3): the same Merchant, the exact paise amount,
     * and a Transaction date from 5 days before to 5 days after the due date. Of several matches the Bill whose due
     * date is nearest to the Transaction date wins; a tie goes to the earlier due date, then the lower id.
     */
    fun paidCandidate(txn: Transaction, unpaid: List<BillOccurrence>, zone: ZoneId): BillOccurrence? {
        if (txn.type != TxnType.DEBIT || txn.isReversal || txn.merchantId == null) return null
        val date = txn.at.atZone(zone).toLocalDate()
        val nearestFirst = compareBy<BillOccurrence> { distance(date, it.dueDate) }.thenBy { it.dueDate }.thenBy { it.id.value }
        return unpaid
            .filter { !it.paid && it.merchantId == txn.merchantId && it.amount == txn.amount }
            .filter { distance(date, it.dueDate) <= BILL_MATCH_WINDOW_DAYS }
            .minWithOrNull(nearestFirst)
    }

    private fun distance(a: LocalDate, b: LocalDate): Long = Math.abs(ChronoUnit.DAYS.between(a, b))

    private fun BillRecurrence.toInterval(): SubscriptionInterval? = when (this) {
        BillRecurrence.NONE -> null
        BillRecurrence.MONTHLY -> SubscriptionInterval.MONTHLY
        BillRecurrence.YEARLY -> SubscriptionInterval.YEARLY
    }
}
