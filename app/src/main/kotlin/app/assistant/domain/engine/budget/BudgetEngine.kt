package app.assistant.domain.engine.budget

import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SignedPaise
import app.assistant.domain.model.TxnType
import java.time.YearMonth
import java.time.ZoneId

/** A monthly spending limit for one category. A category has at most one (Requirement 33.1). */
data class Budget(val category: CategoryId, val amount: Paise)

/** What the Money_Screen shows for a Budget (Requirements 33.3, 33.5, 33.7). */
data class BudgetStatus(
    val spent: Paise,
    /** The Budget amount minus [spent]; negative when over budget. */
    val remaining: SignedPaise,
    /** spent ÷ amount × 100, rounded down; values above 100 are kept. */
    val percentUsed: Long,
    /** The amount by which [spent] exceeds the Budget amount, or null while spent is within it. */
    val overBy: Paise?,
) {
    val isOver: Boolean get() = overBy != null
}

enum class BudgetField { CATEGORY, AMOUNT }

/** Requirement 33.6: the amount must be a Valid_Amount, otherwise the save is rejected and any stored Budget stays. */
object BudgetValidator {
    fun validate(amount: Paise?): FieldErrors<BudgetField> = fieldErrors {
        when {
            amount == null -> add(BudgetField.AMOUNT, ValidationError.EMPTY)
            !amount.isValid -> add(BudgetField.AMOUNT, ValidationError.OUT_OF_RANGE)
        }
    }
}

/** What counts as spending: a debit that is not a Reversal. Shared by Budgets and spending reports so their totals agree. */
object SpendRules {
    fun isSpend(txn: Transaction): Boolean = txn.type == TxnType.DEBIT && !txn.isReversal
}

object BudgetEngine {
    /** Requirement 33.2: debits of [category] dated in [month] in the device zone, excluding credits and Reversals. */
    fun spent(category: CategoryId, month: YearMonth, txns: List<Transaction>, zone: ZoneId): Paise =
        Paise.sum(txns.filter { it.category == category && inMonth(it, month, zone) }.map { it.amount })

    /** Spent in [month] for every category that has any, so the sum over all categories is the month's debit total (Requirement 33.4). */
    fun spentByCategory(month: YearMonth, txns: List<Transaction>, zone: ZoneId): Map<CategoryId, Paise> = txns.filter { inMonth(it, month, zone) }
        .groupBy { it.category }
        .mapValues { (_, list) -> Paise.sum(list.map { it.amount }) }

    /** The total of debit spending in [month]. */
    fun totalSpent(month: YearMonth, txns: List<Transaction>, zone: ZoneId): Paise = Paise.sum(txns.filter { inMonth(it, month, zone) }.map { it.amount })

    /** Requirements 33.3, 33.5 and 33.7 for a Budget whose spent amount is already known. */
    fun status(budget: Budget, spent: Paise): BudgetStatus {
        val amount = budget.amount.value
        require(amount > 0) { "A Budget amount is positive" }
        val remaining = budget.amount - spent
        // Whole part and remainder are scaled separately so large amounts never overflow before the division.
        val percent = spent.value / amount * PERCENT + spent.value % amount * PERCENT / amount
        val over = if (spent > budget.amount) spent.value - amount else null
        return BudgetStatus(spent, remaining, percent, over?.let { Paise(it) })
    }

    private fun inMonth(txn: Transaction, month: YearMonth, zone: ZoneId): Boolean = SpendRules.isSpend(txn) && YearMonth.from(txn.at.atZone(zone)) == month

    private const val PERCENT = 100L
}
