package app.assistant.domain.engine.reports

import app.assistant.domain.engine.budget.SpendRules
import app.assistant.domain.engine.review.ReportCalculator
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.SignedPaise
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Spend in one category for a report period. */
data class CategorySpend(val category: CategoryId, val name: String, val amount: Paise)

/** One category of a monthly report with its change against the previous month (Requirement 35.1). */
data class MonthlyCategoryLine(
    val category: CategoryId,
    val name: String,
    val amount: Paise,
    val previous: Paise,
    /** [amount] minus [previous] in rupees' smallest unit; negative when spending fell. */
    val change: SignedPaise,
    /** The change as a percent of [previous], rounded to the nearest whole number, or null when [previous] is 0. */
    val percent: Long?,
)

data class MonthlySpendingReport(
    val month: YearMonth,
    val total: Paise,
    /** Every category with spend in the month or the one before, largest spend first, ties by name. */
    val lines: List<MonthlyCategoryLine>,
    /** The Reversals dated in the month, shown apart from [total] and not subtracted from it (Requirement 35.6). */
    val reversals: Paise,
)

data class WeeklySpendingReport(
    val week: PeriodInstance,
    val total: Paise,
    /** The 5 categories with the highest spend, in descending order, ties by name A to Z (Requirement 35.2). */
    val top: List<CategorySpend>,
    /** The spend of all other categories combined. */
    val other: Paise,
    val reversals: Paise,
)

/** Category names for the reports. An id with no name is shown as its number so nothing is dropped. */
fun interface CategoryNames {
    fun nameOf(category: CategoryId): String
}

private const val TOP_CATEGORIES = 5
private const val PERCENT = 100L

private val byAmountThenName: Comparator<CategorySpend> =
    compareByDescending<CategorySpend> { it.amount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.category.value }

private fun spendBy(txns: List<Transaction>, zone: ZoneId, inPeriod: (LocalDate) -> Boolean): Map<CategoryId, Paise> =
    txns.filter { SpendRules.isSpend(it) && inPeriod(it.at.atZone(zone).toLocalDate()) }
        .groupBy { it.category }
        .mapValues { (_, list) -> Paise.sum(list.map { it.amount }) }

private fun reversalsIn(txns: List<Transaction>, zone: ZoneId, inPeriod: (LocalDate) -> Boolean): Paise =
    Paise.sum(txns.filter { it.isReversal && inPeriod(it.at.atZone(zone).toLocalDate()) }.map { it.amount })

/** Rounds change ÷ previous × 100 to the nearest whole number, halves away from zero. Exact: no floating point is involved. */
internal fun percentChange(change: SignedPaise, previous: Paise): Long? {
    if (previous.value == 0L) return null
    val scaled = BigDecimal.valueOf(change.value).multiply(BigDecimal.valueOf(PERCENT))
    return scaled.divide(BigDecimal.valueOf(previous.value), 0, RoundingMode.HALF_UP).toLong()
}

/**
 * Requirement 35.1: total spend, spend per category and the change against the previous month. A pure function of
 * the stored Transactions, so a report created late equals one created on time (Requirement 35.5).
 */
fun ReportCalculator.monthlySpending(month: YearMonth, txns: List<Transaction>, zone: ZoneId, names: CategoryNames): MonthlySpendingReport {
    val current = spendBy(txns, zone) { YearMonth.from(it) == month }
    val before = spendBy(txns, zone) { YearMonth.from(it) == month.minusMonths(1) }
    val lines = (current.keys + before.keys).map { category ->
        val amount = current[category] ?: Paise.ZERO
        val previous = before[category] ?: Paise.ZERO
        val change = amount - previous
        MonthlyCategoryLine(category, names.nameOf(category), amount, previous, change, percentChange(change, previous))
    }.sortedWith(
        compareByDescending<MonthlyCategoryLine> { it.amount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.category.value },
    )
    return MonthlySpendingReport(month, Paise.sum(current.values), lines, reversalsIn(txns, zone) { YearMonth.from(it) == month })
}

/** Requirement 35.2: total spend, the 5 highest categories and the rest as "Other". */
fun ReportCalculator.weeklySpending(week: PeriodInstance, txns: List<Transaction>, zone: ZoneId, names: CategoryNames): WeeklySpendingReport {
    val spend = spendBy(txns, zone) { it in week }
    val ranked = spend.map { (category, amount) -> CategorySpend(category, names.nameOf(category), amount) }.sortedWith(byAmountThenName)
    return WeeklySpendingReport(
        week = week,
        total = Paise.sum(spend.values),
        top = ranked.take(TOP_CATEGORIES),
        other = Paise.sum(ranked.drop(TOP_CATEGORIES).map { it.amount }),
        reversals = reversalsIn(txns, zone) { it in week },
    )
}
