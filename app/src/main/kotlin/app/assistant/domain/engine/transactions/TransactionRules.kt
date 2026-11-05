package app.assistant.domain.engine.transactions

import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.dedupe.UserEdits
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.FieldErrorsBuilder
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the User types to add a Transaction by hand (Requirement 32.3). A null field is a missing one. */
data class ManualTransactionInput(val type: TxnType?, val amount: Paise?, val at: Instant?, val merchant: String)

/**
 * An edit of an existing Transaction (Requirement 32.1). A null field is left as it is; only the fields
 * given are validated and marked as edited by the User. A blank merchant clears the Merchant.
 */
data class TransactionEdit(val type: TxnType? = null, val amount: Paise? = null, val at: Instant? = null, val merchant: String? = null) {
    val isEmpty: Boolean get() = type == null && amount == null && at == null && merchant == null
}

object TransactionValidator {
    const val MERCHANT_MAX = 100

    /** Requirement 32.6 for a manual add: every field is required, and the Merchant may not be empty. */
    fun validateNew(input: ManualTransactionInput, today: LocalDate, zone: ZoneId): FieldErrors<EditedField> = fieldErrors {
        check(EditedField.TYPE, input.type != null, ValidationError.EMPTY)
        amountErrors(input.amount, required = true)
        dateErrors(input.at, required = true, today, zone)
        merchantErrors(input.merchant, required = true)
    }

    /** Requirement 32.6 for an edit: only the fields being changed are checked. */
    fun validateEdit(edit: TransactionEdit, today: LocalDate, zone: ZoneId): FieldErrors<EditedField> = fieldErrors {
        if (edit.amount != null) amountErrors(edit.amount, required = true)
        if (edit.at != null) dateErrors(edit.at, required = true, today, zone)
        if (edit.merchant != null) merchantErrors(edit.merchant, required = false)
    }

    private fun FieldErrorsBuilder<EditedField>.amountErrors(amount: Paise?, required: Boolean) {
        when {
            amount == null -> if (required) add(EditedField.AMOUNT, ValidationError.EMPTY)
            !amount.isValid -> add(EditedField.AMOUNT, ValidationError.OUT_OF_RANGE)
        }
    }

    private fun FieldErrorsBuilder<EditedField>.dateErrors(at: Instant?, required: Boolean, today: LocalDate, zone: ZoneId) {
        when {
            at == null -> if (required) add(EditedField.DATE, ValidationError.EMPTY)
            at.atZone(zone).toLocalDate().isAfter(today) -> add(EditedField.DATE, ValidationError.IN_FUTURE)
        }
    }

    private fun FieldErrorsBuilder<EditedField>.merchantErrors(merchant: String, required: Boolean) {
        val text = merchant.trim()
        check(EditedField.MERCHANT, !required || text.isNotEmpty(), ValidationError.EMPTY)
        check(EditedField.MERCHANT, text.length <= MERCHANT_MAX, ValidationError.TOO_LONG)
    }
}

/** Which of the two Transactions being merged supplies a field (Requirement 32.4). */
enum class MergeSide { FIRST, SECOND }

/** The field values the User selected when merging. A field both Transactions agree on needs no choice. */
data class MergeSelection(val amount: MergeSide = MergeSide.FIRST, val date: MergeSide = MergeSide.FIRST, val merchant: MergeSide = MergeSide.FIRST)

enum class MergeError { NOT_FOUND, SAME_TRANSACTION, DIFFERENT_TYPE }

object TransactionMerge {
    /** Requirement 32.8: only Transactions of the same type merge. */
    fun check(first: TxnType, second: TxnType): MergeError? = if (first == second) null else MergeError.DIFFERENT_TYPE

    /**
     * The edits the merged Transaction carries (Requirement 32.4). Where the two disagree, the value the User
     * selected becomes a User edit, so no later message can change it. Where they agree, an edit either one
     * already had is kept. [first] and [second] must be of the same type.
     */
    fun mergedEdits(first: Transaction, second: Transaction, selection: MergeSelection): UserEdits {
        require(first.type == second.type) { "Only Transactions of the same type merge" }

        fun <T> pick(side: MergeSide, field: EditedField, a: T, b: T, same: Boolean): T? = when {
            !same -> if (side == MergeSide.FIRST) a else b
            field in first.editedFields -> a
            field in second.editedFields -> b
            else -> null
        }
        val firstMerchant = first.merchantName.orEmpty()
        val secondMerchant = second.merchantName.orEmpty()
        return UserEdits(
            type = if (EditedField.TYPE in first.editedFields || EditedField.TYPE in second.editedFields) first.type else null,
            amount = pick(selection.amount, EditedField.AMOUNT, first.amount, second.amount, first.amount == second.amount),
            at = pick(selection.date, EditedField.DATE, first.at, second.at, first.at == second.at),
            merchant = pick(
                selection.merchant,
                EditedField.MERCHANT,
                firstMerchant,
                secondMerchant,
                Categoriser.normalise(firstMerchant) == Categoriser.normalise(secondMerchant),
            ),
        )
    }
}
