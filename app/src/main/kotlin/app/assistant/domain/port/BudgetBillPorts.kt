package app.assistant.domain.port

import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillInput
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.budget.Budget
import app.assistant.domain.engine.budget.BudgetField
import app.assistant.domain.engine.budget.BudgetStatus
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.BillId
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.TransactionId
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

/** A Budget with what has been spent against it in one month (Requirement 33). */
data class BudgetLine(val budget: Budget, val status: BudgetStatus)

interface BudgetRepository {
    fun budgets(): Flow<List<Budget>>

    /** Each Budget with its status for [month], in category order. Follows Transactions as they change. */
    fun lines(month: YearMonth): Flow<List<BudgetLine>>

    /**
     * Stores [amount] as the category's only Budget, replacing an earlier one (Requirement 33.1). An amount that is not a
     * Valid_Amount, or an unknown category, changes nothing (Requirement 33.6).
     */
    suspend fun save(category: CategoryId, amount: Paise?): Outcome<Unit, FieldErrors<BudgetField>>

    suspend fun remove(category: CategoryId)
}

/** A bill found in a message, waiting for the User to accept it. The due date is null when the message had none (Requirement 34.2). */
data class BillProposal(val id: Long, val merchantId: MerchantId?, val merchantName: String?, val amount: Paise, val dueDate: LocalDate?)

/** The suggestion that a Transaction paid a Bill, waiting for the User (Requirement 34.3). */
data class BillPaidProposal(val transaction: TransactionId, val bill: BillId)

enum class BillError { NOT_FOUND, ALREADY_PAID }

interface BillRepository {
    /** Unpaid occurrences first by due date, then paid ones. */
    fun bills(): Flow<List<BillOccurrence>>

    fun proposals(): Flow<List<BillProposal>>

    fun paidProposals(): Flow<List<BillPaidProposal>>

    suspend fun get(id: BillId): BillOccurrence?

    /** Stores a new Bill, or updates [existing]. An invalid form changes nothing (Requirements 34.1, 34.6). */
    suspend fun save(input: BillInput, existing: BillId? = null): Outcome<BillId, FieldErrors<BillField>>

    /** Turns a proposal into a Bill with the entered fields and removes the proposal (Requirement 34.2). */
    suspend fun acceptProposal(proposalId: Long, input: BillInput): Outcome<BillId, FieldErrors<BillField>>

    suspend fun dismissProposal(proposalId: Long)

    /**
     * Marks the occurrence paid and, for a recurring Bill, creates the next occurrence (Requirement 34.4). Returns the next
     * occurrence's id, or null for a Bill that does not recur. Its bill-due notification is cancelled (Requirement 34.7).
     */
    suspend fun markPaid(id: BillId): Outcome<BillId?, BillError>

    /** Removes the occurrence and cancels its bill-due notification (Requirement 34.7). */
    suspend fun delete(id: BillId)

    /**
     * Proposes marking the Bill paid that [transaction] pays, once per Transaction and Bill pair (Requirement 34.3).
     * Returns true when a new proposal was made.
     */
    suspend fun proposePaid(transaction: TransactionId): Boolean

    /** Marks the Bill paid, as [markPaid] does, and closes the proposal. */
    suspend fun acceptPaidProposal(transaction: TransactionId, bill: BillId): Outcome<BillId?, BillError>

    suspend fun dismissPaidProposal(transaction: TransactionId, bill: BillId)

    /** Unpaid occurrences due from [from] to [days] days after it, for the Daily_Plan (Requirement 14.6). */
    suspend fun unpaidDueWithin(from: LocalDate, days: Int): List<BillOccurrence>

    /** Records that the bill-due notification for [id] was shown, so it is not made again. */
    suspend fun markNotified(id: BillId)
}

/** Brings the queued bill-due alarms in line with the stored Bills. Called after every change to them and when the app opens. */
fun interface BillAlarmSync {
    suspend fun sync()
}

interface SpendingReportRepository {
    /** Creates the spending report of every week and month that ended since the last run, including missed ones (Requirement 35.5). */
    suspend fun ensureReportsUpTo(today: LocalDate)

    /** Calculated from the Transactions stored now, so any edit shows (Requirement 35.4). */
    suspend fun monthly(month: YearMonth): MonthlySpendingReport

    suspend fun weekly(week: PeriodInstance): WeeklySpendingReport

    /** The latest month and week that have a report. */
    fun latestMonth(): Flow<YearMonth?>

    fun latestWeek(): Flow<PeriodInstance?>
}
