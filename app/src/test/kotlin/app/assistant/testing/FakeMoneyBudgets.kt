package app.assistant.testing

import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillInput
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.bills.BillRules
import app.assistant.domain.engine.bills.BillValidator
import app.assistant.domain.engine.budget.Budget
import app.assistant.domain.engine.budget.BudgetEngine
import app.assistant.domain.engine.budget.BudgetField
import app.assistant.domain.engine.budget.BudgetValidator
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.BillError
import app.assistant.domain.port.BillPaidProposal
import app.assistant.domain.port.BillProposal
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.BudgetLine
import app.assistant.domain.port.BudgetRepository
import app.assistant.domain.port.SpendingReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

/**
 * A [BudgetRepository] that keeps Budgets in memory. [spent] is the same in every month, which is enough to test the screens;
 * [requestedMonths] records the months the screen asked for.
 */
class FakeMoneyBudgetRepository(initial: Map<CategoryId, Paise> = emptyMap(), private val spent: Map<CategoryId, Paise> = emptyMap()) : BudgetRepository {
    private val stored = MutableStateFlow(initial)
    val requestedMonths = mutableListOf<YearMonth>()
    val removed = mutableListOf<CategoryId>()

    val amounts: Map<CategoryId, Paise> get() = stored.value

    override fun budgets(): Flow<List<Budget>> = stored.map { all -> all.map { (category, amount) -> Budget(category, amount) } }

    override fun lines(month: YearMonth): Flow<List<BudgetLine>> {
        requestedMonths += month
        return stored.map { all ->
            all.entries.sortedBy { it.key.value }.map { (category, amount) ->
                val budget = Budget(category, amount)
                BudgetLine(budget, BudgetEngine.status(budget, spent[category] ?: Paise.ZERO))
            }
        }
    }

    override suspend fun save(category: CategoryId, amount: Paise?): Outcome<Unit, FieldErrors<BudgetField>> {
        val errors = BudgetValidator.validate(amount)
        if (amount == null || !errors.isValid) return Outcome.Err(errors)
        stored.value = stored.value + (category to amount)
        return Outcome.Ok(Unit)
    }

    override suspend fun remove(category: CategoryId) {
        removed += category
        stored.value = stored.value - category
    }
}

/** A [BillRepository] that keeps Bills, proposals and paid proposals in memory and records what the screen did. */
class FakeMoneyBillRepository(
    initial: List<BillOccurrence> = emptyList(),
    proposals: List<BillProposal> = emptyList(),
    paidProposals: List<BillPaidProposal> = emptyList(),
) : BillRepository {
    private val all = MutableStateFlow(initial)
    private val proposalList = MutableStateFlow(proposals)
    private val paidList = MutableStateFlow(paidProposals)
    private var nextId = FIRST_NEW_ID

    val saved = mutableListOf<BillInput>()
    val accepted = mutableListOf<Long>()
    val dismissed = mutableListOf<Long>()
    val acceptedPaid = mutableListOf<Pair<TransactionId, BillId>>()
    val dismissedPaid = mutableListOf<Pair<TransactionId, BillId>>()
    val deleted = mutableListOf<BillId>()

    override fun bills(): Flow<List<BillOccurrence>> = all

    override fun proposals(): Flow<List<BillProposal>> = proposalList

    override fun paidProposals(): Flow<List<BillPaidProposal>> = paidList

    override suspend fun get(id: BillId): BillOccurrence? = all.value.firstOrNull { it.id == id }

    override suspend fun save(input: BillInput, existing: BillId?): Outcome<BillId, FieldErrors<BillField>> {
        val errors = BillValidator.validate(input)
        if (!errors.isValid) return Outcome.Err(errors)
        saved += input
        return Outcome.Ok(existing ?: BillId(nextId++))
    }

    override suspend fun acceptProposal(proposalId: Long, input: BillInput): Outcome<BillId, FieldErrors<BillField>> {
        val errors = BillValidator.validate(input)
        if (!errors.isValid) return Outcome.Err(errors)
        saved += input
        accepted += proposalId
        proposalList.value = proposalList.value.filterNot { it.id == proposalId }
        return Outcome.Ok(BillId(nextId++))
    }

    override suspend fun dismissProposal(proposalId: Long) {
        dismissed += proposalId
        proposalList.value = proposalList.value.filterNot { it.id == proposalId }
    }

    override suspend fun markPaid(id: BillId): Outcome<BillId?, BillError> {
        val bill = all.value.firstOrNull { it.id == id } ?: return Outcome.Err(BillError.NOT_FOUND)
        if (bill.paid) return Outcome.Err(BillError.ALREADY_PAID)
        val next = if (bill.recurrence == BillRecurrence.NONE) {
            null
        } else {
            bill.copy(
                id = BillId(nextId++),
                dueDate = BillRules.nextDueOnAnchor(bill.anchorDay, bill.dueDate, bill.recurrence),
                paid = false,
                notified = false,
            )
        }
        all.value = all.value.map { if (it.id == id) it.copy(paid = true) else it } + listOfNotNull(next)
        return Outcome.Ok(next?.id)
    }

    override suspend fun delete(id: BillId) {
        deleted += id
        all.value = all.value.filterNot { it.id == id }
    }

    override suspend fun proposePaid(transaction: TransactionId): Boolean = false

    override suspend fun acceptPaidProposal(transaction: TransactionId, bill: BillId): Outcome<BillId?, BillError> {
        acceptedPaid += transaction to bill
        paidList.value = paidList.value.filterNot { it.transaction == transaction && it.bill == bill }
        return markPaid(bill)
    }

    override suspend fun dismissPaidProposal(transaction: TransactionId, bill: BillId) {
        dismissedPaid += transaction to bill
        paidList.value = paidList.value.filterNot { it.transaction == transaction && it.bill == bill }
    }

    override suspend fun unpaidDueWithin(from: LocalDate, days: Int): List<BillOccurrence> =
        all.value.filter { !it.paid && !it.dueDate.isBefore(from) && !it.dueDate.isAfter(from.plusDays(days.toLong())) }

    override suspend fun markNotified(id: BillId) = Unit

    private companion object {
        const val FIRST_NEW_ID = 100L
    }
}

/** A [SpendingReportRepository] over fixed reports; a period with no report is an empty one. */
class FakeMoneyReportRepository(
    private val months: Map<YearMonth, MonthlySpendingReport> = emptyMap(),
    private val weeks: Map<LocalDate, WeeklySpendingReport> = emptyMap(),
    private val latestMonth: YearMonth? = null,
    private val latestWeek: PeriodInstance? = null,
) : SpendingReportRepository {
    override suspend fun ensureReportsUpTo(today: LocalDate) = Unit

    override suspend fun monthly(month: YearMonth): MonthlySpendingReport = months[month] ?: MonthlySpendingReport(month, Paise.ZERO, emptyList(), Paise.ZERO)

    /** Weeks are keyed by their first day. */
    override suspend fun weekly(week: PeriodInstance): WeeklySpendingReport =
        weeks[week.first] ?: WeeklySpendingReport(week, Paise.ZERO, emptyList(), Paise.ZERO, Paise.ZERO)

    override fun latestMonth(): Flow<YearMonth?> = flowOf(latestMonth)

    override fun latestWeek(): Flow<PeriodInstance?> = flowOf(latestWeek)
}
