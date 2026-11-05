package app.assistant.testing

import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillInput
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.quality.ParseOutcomeKind
import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.BillId
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.BillAlarmSync
import app.assistant.domain.port.BillError
import app.assistant.domain.port.BillPaidProposal
import app.assistant.domain.port.BillProposal
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.SpendingReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.YearMonth

/** A [BillRepository] over a fixed list, for tests of code that reads Bills. Mutating calls it does not need fail loudly. */
class FakeBillRepository(initial: List<BillOccurrence> = emptyList()) : BillRepository {
    private val all = MutableStateFlow(initial)
    val notified = mutableListOf<BillId>()

    override fun bills(): Flow<List<BillOccurrence>> = all

    override fun proposals(): Flow<List<BillProposal>> = flowOf(emptyList())

    override fun paidProposals(): Flow<List<BillPaidProposal>> = flowOf(emptyList())

    override suspend fun get(id: BillId): BillOccurrence? = all.value.firstOrNull { it.id == id }

    override suspend fun save(input: BillInput, existing: BillId?): Outcome<BillId, FieldErrors<BillField>> = error("not used in tests")

    override suspend fun acceptProposal(proposalId: Long, input: BillInput): Outcome<BillId, FieldErrors<BillField>> = error("not used in tests")

    override suspend fun dismissProposal(proposalId: Long) = error("not used in tests")

    override suspend fun markPaid(id: BillId): Outcome<BillId?, BillError> = error("not used in tests")

    override suspend fun delete(id: BillId) = error("not used in tests")

    override suspend fun proposePaid(transaction: TransactionId): Boolean = error("not used in tests")

    override suspend fun acceptPaidProposal(transaction: TransactionId, bill: BillId): Outcome<BillId?, BillError> = error("not used in tests")

    override suspend fun dismissPaidProposal(transaction: TransactionId, bill: BillId) = error("not used in tests")

    override suspend fun unpaidDueWithin(from: LocalDate, days: Int): List<BillOccurrence> =
        all.value.filter { !it.paid && !it.dueDate.isBefore(from) && !it.dueDate.isAfter(from.plusDays(days.toLong())) }

    override suspend fun markNotified(id: BillId) {
        notified += id
    }
}

class FakeBillAlarmSync(private val fails: Boolean = false) : BillAlarmSync {
    var syncs = 0

    override suspend fun sync() {
        syncs++
        check(!fails) { "sync failed" }
    }
}

class FakeSpendingReportRepository : SpendingReportRepository {
    var catchUps = 0

    override suspend fun ensureReportsUpTo(today: LocalDate) {
        catchUps++
    }

    override suspend fun monthly(month: YearMonth): MonthlySpendingReport = error("not used in tests")

    override suspend fun weekly(week: PeriodInstance): WeeklySpendingReport = error("not used in tests")

    override fun latestMonth(): Flow<YearMonth?> = flowOf(null)

    override fun latestWeek(): Flow<PeriodInstance?> = flowOf(null)
}

/** Remembers what the Quality_Monitor was told. */
class RecordingQualityMonitor(private val fails: Boolean = false) : QualityMonitor {
    data class Parse(val origin: String, val day: LocalDate, val kind: ParseOutcomeKind)

    val parses = mutableListOf<Parse>()
    val corrections = mutableListOf<SourceRef>()

    override suspend fun recordParse(origin: MessageOrigin, day: LocalDate, outcome: ParseOutcomeKind) {
        check(!fails) { "counter unavailable" }
        parses += Parse(origin.value, day, outcome)
    }

    override suspend fun recordCorrection(sourceRef: SourceRef) {
        check(!fails) { "counter unavailable" }
        corrections += sourceRef
    }

    override fun accuracy(origin: MessageOrigin, today: LocalDate) = error("not used in tests")
}
