package app.assistant.ui.money.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillInput
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.bills.BillValidator
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.errorOrNull
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.BillError
import app.assistant.domain.port.BillProposal
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.money.budgets.MoneyAmountText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the User typed in the Bill form. The amount stays text until it is checked. */
data class BillForm(val name: String, val amountText: String, val dueDate: LocalDate?, val recurrence: BillRecurrence, val merchant: String? = null)

/** The suggestion that [transaction] paid [bill] (Requirement 34.3). */
data class PaidProposalRow(val transaction: TransactionId, val bill: BillOccurrence)

/** What happened to the last Bill the User marked paid. */
sealed interface BillNotice {
    /** A recurring Bill: the next occurrence was created (Requirement 34.4). */
    data class PaidWithNext(val name: String, val next: LocalDate) : BillNotice

    data class Paid(val name: String) : BillNotice

    data object Failed : BillNotice
}

data class BillsUi(
    val loading: Boolean = true,
    val today: LocalDate,
    /** Unpaid occurrences, earliest due date first. */
    val unpaid: List<BillOccurrence> = emptyList(),
    val paid: List<BillOccurrence> = emptyList(),
    val proposals: List<BillProposal> = emptyList(),
    val paidProposals: List<PaidProposalRow> = emptyList(),
    val notice: BillNotice? = null,
)

@HiltViewModel
class BillsViewModel @Inject constructor(private val bills: BillRepository, time: TimeSource) : ViewModel() {

    private val today: LocalDate = time.today()
    private val notice = MutableStateFlow<BillNotice?>(null)
    private val _state = MutableStateFlow(BillsUi(today = today))
    val state: StateFlow<BillsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(bills.bills(), bills.proposals(), bills.paidProposals(), notice) { all, proposals, paidProposals, shownNotice ->
                val byId = all.associateBy { it.id }
                BillsUi(
                    loading = false,
                    today = today,
                    unpaid = all.filter { !it.paid },
                    paid = all.filter { it.paid },
                    proposals = proposals,
                    paidProposals = paidProposals.mapNotNull { p -> byId[p.bill]?.let { PaidProposalRow(p.transaction, it) } },
                    notice = shownNotice,
                )
            }.collect { _state.value = it }
        }
    }

    /** Stores a new Bill, or the changes to [existing]. [onResult] gets null on success, otherwise the field errors (Requirements 34.1, 34.6). */
    fun save(existing: BillId?, form: BillForm, onResult: (FieldErrors<BillField>?) -> Unit) {
        submit(form, onResult) { input -> bills.save(input, existing) }
    }

    /** Turns a proposal into a Bill with the entered fields (Requirement 34.2). */
    fun acceptProposal(proposalId: Long, form: BillForm, onResult: (FieldErrors<BillField>?) -> Unit) {
        submit(form, onResult) { input -> bills.acceptProposal(proposalId, input) }
    }

    fun dismissProposal(proposalId: Long) {
        viewModelScope.launch { bills.dismissProposal(proposalId) }
    }

    fun markPaid(bill: BillOccurrence) {
        viewModelScope.launch { notice.value = describe(bill, bills.markPaid(bill.id)) }
    }

    fun acceptPaidProposal(row: PaidProposalRow) {
        viewModelScope.launch { notice.value = describe(row.bill, bills.acceptPaidProposal(row.transaction, row.bill.id)) }
    }

    fun dismissPaidProposal(row: PaidProposalRow) {
        viewModelScope.launch { bills.dismissPaidProposal(row.transaction, row.bill.id) }
    }

    fun delete(id: BillId) {
        viewModelScope.launch { bills.delete(id) }
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun submit(form: BillForm, onResult: (FieldErrors<BillField>?) -> Unit, store: suspend (BillInput) -> Outcome<BillId, FieldErrors<BillField>>) {
        val parsed = MoneyAmountText.parse(form.amountText)
        val input = BillInput(form.name, (parsed as? Outcome.Ok)?.value, form.dueDate, form.recurrence, form.merchant)
        val checked = BillValidator.validate(input)
        val amountError = parsed.errorOrNull()
        val errors = if (amountError == null) checked else FieldErrors(checked.errors + (BillField.AMOUNT to amountError))
        if (!errors.isValid) {
            onResult(errors)
            return
        }
        viewModelScope.launch {
            val result = store(input)
            onResult(if (result is Outcome.Err) result.error else null)
        }
    }

    private suspend fun describe(bill: BillOccurrence, result: Outcome<BillId?, BillError>): BillNotice = when (result) {
        is Outcome.Err -> BillNotice.Failed
        is Outcome.Ok -> {
            val next = result.value?.let { bills.get(it) }
            if (next == null) BillNotice.Paid(bill.name) else BillNotice.PaidWithNext(bill.name, next.dueDate)
        }
    }
}
