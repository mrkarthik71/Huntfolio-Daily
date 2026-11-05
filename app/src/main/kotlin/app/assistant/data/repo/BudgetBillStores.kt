package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.DbProvider
import app.assistant.data.db.dao.BillProposalRow
import app.assistant.data.db.dao.MetricTotal
import app.assistant.data.db.entity.BillEntity
import app.assistant.data.db.entity.BillPaidProposalEntity
import app.assistant.data.db.entity.BudgetEntity
import app.assistant.data.db.entity.QualityCounterEntity
import app.assistant.data.db.entity.ReportEntity
import app.assistant.domain.engine.bills.BillAlarms
import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillInput
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.bills.BillRules
import app.assistant.domain.engine.bills.BillValidator
import app.assistant.domain.engine.budget.Budget
import app.assistant.domain.engine.budget.BudgetEngine
import app.assistant.domain.engine.budget.BudgetField
import app.assistant.domain.engine.budget.BudgetValidator
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.engine.quality.MessageReceipt
import app.assistant.domain.engine.quality.QualityMetric
import app.assistant.domain.engine.quality.QualityStore
import app.assistant.domain.engine.quality.QualityTotals
import app.assistant.domain.engine.reports.CategoryNames
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.engine.reports.monthlySpending
import app.assistant.domain.engine.reports.weeklySpending
import app.assistant.domain.engine.review.ReportCalculator
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Paise
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.AlarmStore
import app.assistant.domain.port.BillAlarmSync
import app.assistant.domain.port.BillError
import app.assistant.domain.port.BillPaidProposal
import app.assistant.domain.port.BillProposal
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.BudgetLine
import app.assistant.domain.port.BudgetRepository
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.SpendingReportRepository
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

private const val IGNORED = -1L
private const val OPEN = "OPEN"
private const val ACCEPTED = "ACCEPTED"
private const val DISMISSED = "DISMISSED"

internal fun BillEntity.toDomain() = BillOccurrence(
    id = BillId(id),
    name = name,
    amount = Paise(amountPaise),
    dueDate = LocalDate.parse(dueDate),
    recurrence = BillRecurrence.valueOf(recurrence),
    anchorDay = anchorDay,
    merchantId = merchantId?.let(::MerchantId),
    paid = paid,
    seriesId = seriesId,
    notified = notified,
)

/** Monthly Budgets, one per category, and what has been spent against them (Requirement 33). */
@Singleton
class RoomBudgetRepository @Inject constructor(private val holder: DbProvider, private val transactions: TransactionRepository, private val time: TimeSource) :
    BudgetRepository {
    override fun budgets(): Flow<List<Budget>> = holder.database().budgetDao().observe().map { rows -> rows.map { it.toDomain() } }

    override fun lines(month: YearMonth): Flow<List<BudgetLine>> = combine(budgets(), transactions.transactions()) { budgets, txns ->
        val spent = BudgetEngine.spentByCategory(month, txns, time.zone())
        budgets.map { BudgetLine(it, BudgetEngine.status(it, spent[it.category] ?: Paise.ZERO)) }
    }

    override suspend fun save(category: CategoryId, amount: Paise?): Outcome<Unit, FieldErrors<BudgetField>> {
        val dao = holder.database().budgetDao()
        val amountErrors = BudgetValidator.validate(amount).errors
        val categoryErrors = if (dao.categoryCount(category.value) == 0) mapOf(BudgetField.CATEGORY to ValidationError.NOT_ALLOWED) else emptyMap()
        val errors = FieldErrors(categoryErrors + amountErrors)
        if (!errors.isValid || amount == null) return Outcome.Err(errors)
        dao.put(BudgetEntity(category.value, amount.value))
        return Outcome.Ok(Unit)
    }

    override suspend fun remove(category: CategoryId) {
        holder.database().budgetDao().delete(category.value)
    }

    private fun BudgetEntity.toDomain() = Budget(CategoryId(categoryId), Paise(amountPaise))
}

/** Bills, the proposals made from bill messages and from payments, and the next occurrence of a recurring Bill (Requirement 34). */
@Singleton
class RoomBillRepository @Inject constructor(
    private val holder: DbProvider,
    private val transactions: TransactionRepository,
    private val time: TimeSource,
    private val alarmSync: BillAlarmSync,
) : BillRepository {
    private val db get() = holder.database()
    private val dao get() = db.billDao()

    override fun bills(): Flow<List<BillOccurrence>> = dao.observe().map { rows -> rows.map { it.toDomain() } }

    override fun proposals(): Flow<List<BillProposal>> = dao.observeProposals().map { rows -> rows.map { it.toDomain() } }

    override fun paidProposals(): Flow<List<BillPaidProposal>> =
        dao.observePaidProposals().map { rows -> rows.map { BillPaidProposal(TransactionId(it.transactionId), BillId(it.billId)) } }

    override suspend fun get(id: BillId): BillOccurrence? = dao.get(id.value)?.toDomain()

    override suspend fun save(input: BillInput, existing: BillId?): Outcome<BillId, FieldErrors<BillField>> {
        val errors = BillValidator.validate(input)
        val due = input.dueDate
        val amount = input.amount
        if (!errors.isValid || due == null || amount == null) return Outcome.Err(errors)
        val saved = db.withTransaction<BillId?> {
            val merchant = input.merchant?.let { MerchantResolver(db.merchantCatalogDao()).resolve(it) }
            if (existing == null) {
                insertBill(input.name.trim(), amount, due, input.recurrence, merchant)
            } else {
                val current = dao.get(existing.value) ?: return@withTransaction null
                dao.update(
                    current.copy(
                        name = input.name.trim(),
                        amountPaise = amount.value,
                        dueDate = due.toString(),
                        recurrence = input.recurrence.name,
                        anchorDay = due.dayOfMonth,
                        merchantId = merchant ?: current.merchantId,
                        // A new due date is a new occasion for the bill-due notification.
                        notified = current.notified && current.dueDate == due.toString(),
                    ),
                )
                existing
            }
        }
        if (saved == null) return Outcome.Err(FieldErrors(mapOf(BillField.NAME to ValidationError.NOT_ALLOWED)))
        alarmSync.sync()
        return Outcome.Ok(saved)
    }

    private suspend fun insertBill(name: String, amount: Paise, due: LocalDate, recurrence: BillRecurrence, merchant: Long?): BillId {
        val id = dao.insert(
            BillEntity(
                name = name,
                amountPaise = amount.value,
                dueDate = due.toString(),
                recurrence = recurrence.name,
                anchorDay = due.dayOfMonth,
                merchantId = merchant,
                paid = false,
                seriesId = 0,
                notified = false,
            ),
        )
        dao.setSeries(id, id)
        return BillId(id)
    }

    override suspend fun acceptProposal(proposalId: Long, input: BillInput): Outcome<BillId, FieldErrors<BillField>> {
        val errors = BillValidator.validate(input)
        val due = input.dueDate
        val amount = input.amount
        if (!errors.isValid || due == null || amount == null) return Outcome.Err(errors)
        val saved = db.withTransaction<BillId?> {
            val proposal = dao.proposal(proposalId) ?: return@withTransaction null
            dao.deleteProposal(proposalId)
            insertBill(input.name.trim(), amount, due, input.recurrence, proposal.merchantId)
        }
        if (saved == null) return Outcome.Err(FieldErrors(mapOf(BillField.NAME to ValidationError.NOT_ALLOWED)))
        alarmSync.sync()
        return Outcome.Ok(saved)
    }

    override suspend fun dismissProposal(proposalId: Long) {
        dao.deleteProposal(proposalId)
    }

    override suspend fun markPaid(id: BillId): Outcome<BillId?, BillError> {
        val result = db.withTransaction { markPaidIn(id) }
        if (result is Outcome.Ok) alarmSync.sync()
        return result
    }

    /** Marks paid, drops the other pending suggestions for this occurrence and creates the next one (Requirement 34.4). */
    private suspend fun markPaidIn(id: BillId): Outcome<BillId?, BillError> {
        val bill = dao.get(id.value) ?: return Outcome.Err(BillError.NOT_FOUND)
        if (bill.paid) return Outcome.Err(BillError.ALREADY_PAID)
        dao.setPaid(id.value)
        dao.deleteOpenPaidProposals(id.value)
        val recurrence = BillRecurrence.valueOf(bill.recurrence)
        if (recurrence == BillRecurrence.NONE) return Outcome.Ok(null)
        val next = BillRules.nextDueOnAnchor(bill.anchorDay, LocalDate.parse(bill.dueDate), recurrence)
        val nextId = dao.insert(bill.copy(id = 0, dueDate = next.toString(), paid = false, notified = false))
        return Outcome.Ok(BillId(nextId))
    }

    override suspend fun delete(id: BillId) {
        db.withTransaction {
            dao.delete(id.value)
            dao.deleteAllPaidProposals(id.value)
        }
        alarmSync.sync()
    }

    override suspend fun proposePaid(transaction: TransactionId): Boolean {
        val txn = transactions.get(transaction) ?: return false
        val unpaid = dao.unpaid().map { it.toDomain() }
        val match = BillRules.paidCandidate(txn, unpaid, time.zone()) ?: return false
        return dao.insertPaidProposal(BillPaidProposalEntity(transaction.value, match.id.value, OPEN)) != IGNORED
    }

    override suspend fun acceptPaidProposal(transaction: TransactionId, bill: BillId): Outcome<BillId?, BillError> {
        val result = db.withTransaction<Outcome<BillId?, BillError>> {
            val proposal = dao.paidProposal(transaction.value, bill.value)
            val stored = dao.get(bill.value)
            when {
                proposal == null || proposal.status != OPEN || stored == null -> Outcome.Err(BillError.NOT_FOUND)
                stored.paid -> Outcome.Err(BillError.ALREADY_PAID)
                else -> {
                    dao.setPaidProposalStatus(transaction.value, bill.value, ACCEPTED)
                    markPaidIn(bill)
                }
            }
        }
        if (result is Outcome.Ok) alarmSync.sync()
        return result
    }

    override suspend fun dismissPaidProposal(transaction: TransactionId, bill: BillId) {
        dao.setPaidProposalStatus(transaction.value, bill.value, DISMISSED)
    }

    override suspend fun unpaidDueWithin(from: LocalDate, days: Int): List<BillOccurrence> =
        dao.unpaidBetween(from.toString(), from.plusDays(days.toLong()).toString()).map { it.toDomain() }

    override suspend fun markNotified(id: BillId) {
        dao.setNotified(id.value)
    }

    private fun BillProposalRow.toDomain() = BillProposal(
        id = id,
        merchantId = merchantId?.let(::MerchantId),
        merchantName = merchantName,
        amount = Paise(amountPaise),
        dueDate = dueDate?.let(LocalDate::parse),
    )
}

/**
 * Aligns the queued bill-due alarms with the stored Bills and re-arms the system alarm (Requirements 34.5, 34.7).
 * The alarm times follow the morning brief time and the BILL_DUE switch in the settings.
 */
@Singleton
class RoomBillAlarmSync @Inject constructor(
    private val holder: DbProvider,
    alarms: AlarmStore,
    private val log: NotificationLog,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val scheduling: NotificationScheduling,
) : BillAlarmSync {
    private val billAlarms = BillAlarms(alarms)

    override suspend fun sync() {
        val s = settings.current()
        val bills = holder.database().billDao().unpaid().map { it.toDomain() }
        val handled = log.handledRefKeys(NotificationType.BILL_DUE)
        billAlarms.sync(bills, handled, NotificationType.BILL_DUE in s.enabledTypes, s.morningBrief, time.now(), time.zone())
        scheduling.refresh()
    }
}

/** Spending reports: which periods have one is stored, what they show is calculated from the Transactions stored now (Requirement 35). */
@Singleton
class RoomSpendingReportRepository @Inject constructor(
    private val holder: DbProvider,
    private val transactions: TransactionRepository,
    private val settings: SettingsStore,
    private val time: TimeSource,
) : SpendingReportRepository {
    private val db get() = holder.database()

    override suspend fun ensureReportsUpTo(today: LocalDate) {
        val zone = time.zone()
        val firstAt = transactions.transactions().first().minOfOrNull { it.at } ?: return
        val firstDay = firstAt.atZone(zone).toLocalDate()
        val dao = db.reportDao()
        val sinceWeek = dao.lastPeriod(WEEKLY)?.let { LocalDate.parse(it).plusDays(WEEK_LAST_OFFSET) }
        val weeks = ReportCalculator.endedWeeks(sinceWeek, today, settings.current().lastDayOfWeek, firstDay)
        val current = YearMonth.from(today)
        val firstMonth = dao.lastPeriod(MONTHLY)?.let { YearMonth.from(LocalDate.parse(it)).plusMonths(1) } ?: YearMonth.from(firstDay)
        val months = generateSequence(firstMonth) { it.plusMonths(1) }.takeWhile { it < current }.toList()
        val now = time.now().toEpochMilli()
        db.withTransaction {
            weeks.forEach { dao.insert(ReportEntity(WEEKLY, it.first.toString(), now)) }
            months.forEach { dao.insert(ReportEntity(MONTHLY, it.atDay(1).toString(), now)) }
        }
    }

    override suspend fun monthly(month: YearMonth): MonthlySpendingReport =
        ReportCalculator.monthlySpending(month, transactions.transactions().first(), time.zone(), categoryNames())

    override suspend fun weekly(week: PeriodInstance): WeeklySpendingReport =
        ReportCalculator.weeklySpending(week, transactions.transactions().first(), time.zone(), categoryNames())

    override fun latestMonth(): Flow<YearMonth?> = db.reportDao().latest(MONTHLY).map { r -> r?.let { YearMonth.from(LocalDate.parse(it.periodFirstDay)) } }

    override fun latestWeek(): Flow<PeriodInstance?> =
        db.reportDao().latest(WEEKLY).map { r -> r?.let { Periods.weekEndingOn(LocalDate.parse(it.periodFirstDay).plusDays(WEEK_LAST_OFFSET)) } }

    private suspend fun categoryNames(): CategoryNames {
        val names = db.categoryDao().all().associate { CategoryId(it.id) to it.name }
        return CategoryNames { names[it] ?: "Category ${it.value}" }
    }

    private companion object {
        const val WEEKLY = "SPENDING_WEEKLY"
        const val MONTHLY = "SPENDING_MONTHLY"
        const val WEEK_LAST_OFFSET = 6L
    }
}

/**
 * The Quality_Monitor's counters in `quality_counter` (Requirement 36.4). Every row is a count for an origin and a day.
 * A corrected message is remembered by a marker row keyed by its Source_Ref, so a message is counted once.
 */
@Singleton
class RoomQualityStore @Inject constructor(private val holder: DbProvider, private val time: TimeSource) : QualityStore {
    private val db get() = holder.database()

    override suspend fun add(origin: MessageOrigin, day: LocalDate, metric: QualityMetric, count: Int) {
        db.withTransaction {
            db.qualityDao().insertIfMissing(QualityCounterEntity(origin.value, day.toString(), metric.name, 0))
            db.qualityDao().increment(origin.value, day.toString(), metric.name, count)
        }
    }

    override suspend fun markCorrected(ref: SourceRef): Boolean =
        db.qualityDao().insertIfMissing(QualityCounterEntity(ref.value, MARKER_DAY, MARKER_METRIC, 1)) != IGNORED

    override suspend fun receipt(ref: SourceRef): MessageReceipt? = db.transactionDao().factsByRefs(listOf(ref.value)).firstOrNull()?.let {
        MessageReceipt(MessageOrigin(it.origin), Instant.ofEpochMilli(it.receivedAt).atZone(time.zone()).toLocalDate())
    }

    override fun totals(origin: MessageOrigin, from: LocalDate, to: LocalDate): Flow<QualityTotals> =
        db.qualityDao().observeTotals(origin.value, from.toString(), to.toString()).map { it.toTotals() }

    override suspend fun totalsNow(origin: MessageOrigin, from: LocalDate, to: LocalDate): QualityTotals =
        db.qualityDao().totals(origin.value, from.toString(), to.toString()).toTotals()

    private fun List<MetricTotal>.toTotals(): QualityTotals {
        fun of(metric: QualityMetric) = firstOrNull { it.metric == metric.name }?.total ?: 0
        return QualityTotals(of(QualityMetric.PARSED), of(QualityMetric.REVIEW), of(QualityMetric.CORRECTED))
    }

    private companion object {
        const val MARKER_DAY = "ref"
        const val MARKER_METRIC = "CORRECTED_REF"
    }
}
