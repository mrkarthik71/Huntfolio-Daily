package app.assistant.ui.money.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.engine.transactions.TransactionMerge
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.CategoryChangeError
import app.assistant.domain.port.CategoryOffer
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** What the User typed in the editor. The amount stays text until it is checked. */
data class TransactionForm(val type: TxnType, val amountText: String, val date: LocalDate?, val merchant: String)

/** A message above the list. Errors are shown with a warning icon as well as text. */
data class TxnNotice(val kind: NoticeKind, val count: Int = 0) {
    val isError: Boolean
        get() = when (kind) {
            NoticeKind.NOT_FOUND, NoticeKind.UNKNOWN_CATEGORY, NoticeKind.MERGE_DIFFERENT_TYPE, NoticeKind.MERGE_SAME -> true
            else -> false
        }
}

enum class NoticeKind {
    ADDED,
    SAVED,
    MERGED,
    DELETED,
    CATEGORY_CHANGED,
    CATEGORY_APPLIED,
    NOT_FOUND,
    UNKNOWN_CATEGORY,
    MERGE_DIFFERENT_TYPE,
    MERGE_SAME,
}

sealed interface TransactionsDialog {
    /** [target] is null when adding by hand. */
    data class Editor(val target: Transaction?, val errors: FieldErrors<EditedField> = FieldErrors.none()) : TransactionsDialog

    data class Merge(val first: Transaction, val second: Transaction) : TransactionsDialog

    data class ConfirmDelete(val target: Transaction) : TransactionsDialog

    data class PickCategory(val target: Transaction) : TransactionsDialog

    data class Offer(val offer: CategoryOffer, val categoryName: String) : TransactionsDialog
}

data class TransactionsUi(
    val loading: Boolean = true,
    /** Newest first. */
    val items: List<Transaction> = emptyList(),
    val zone: ZoneId = ZoneId.of("UTC"),
    val today: LocalDate = LocalDate.ofEpochDay(0),
    val selecting: Boolean = false,
    val selected: List<TransactionId> = emptyList(),
    val dialog: TransactionsDialog? = null,
    val notice: TxnNotice? = null,
)

/** The name of a category, or Uncategorised when the id is not a shipped one. */
fun categoryNameOf(id: CategoryId): String = BuiltinCategories.NAMES.getOrNull((id.value - 1).toInt()) ?: BuiltinCategories.UNCATEGORISED_NAME

/** Reads and writes the amount text of the forms. Money is never a floating point number. */
object MoneyText {
    private val PATTERN = Regex("^[0-9]{1,12}(\\.[0-9]{1,2})?$")

    /** Returns null when [text] is not a rupee amount with at most two decimals. A leading rupee sign and commas are ignored. */
    fun parse(text: String): Paise? {
        val clean = text.trim().removePrefix("₹").replace(",", "").trim()
        if (!PATTERN.matches(clean)) return null
        return Paise(BigDecimal(clean).movePointRight(2).longValueExact())
    }

    /** The amount as editable text: 1234 or 1234.50. */
    fun plain(amount: Paise): String {
        val rupees = amount.value / 100
        val fraction = (amount.value % 100).toInt()
        return if (fraction == 0) rupees.toString() else rupees.toString() + "." + fraction.toString().padStart(2, '0')
    }
}

private data class Local(
    val selecting: Boolean = false,
    val selected: List<TransactionId> = emptyList(),
    val dialog: TransactionsDialog? = null,
    val notice: TxnNotice? = null,
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(private val transactions: TransactionRepository, private val time: TimeSource) : ViewModel() {

    private val local = MutableStateFlow(Local())

    val state: StateFlow<TransactionsUi> = combine(transactions.transactions(), local) { items, l ->
        TransactionsUi(
            loading = false,
            items = items,
            zone = time.zone(),
            today = time.today(),
            selecting = l.selecting,
            selected = l.selected,
            dialog = l.dialog,
            notice = l.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransactionsUi())

    fun dismissNotice() = local.update { it.copy(notice = null) }

    fun dismissDialog() = local.update { it.copy(dialog = null) }

    fun startAdd() = local.update { it.copy(dialog = TransactionsDialog.Editor(null)) }

    fun startEdit(target: Transaction) = local.update { it.copy(dialog = TransactionsDialog.Editor(target)) }

    fun submit(form: TransactionForm) {
        val editor = local.value.dialog as? TransactionsDialog.Editor ?: return
        val zone = time.zone()
        val amount = MoneyText.parse(form.amountText)
        val localErrors = LinkedHashMap<EditedField, ValidationError>()
        if (form.amountText.isBlank()) {
            localErrors[EditedField.AMOUNT] = ValidationError.EMPTY
        } else if (amount == null) {
            localErrors[EditedField.AMOUNT] = ValidationError.NOT_A_NUMBER
        }
        if (form.date == null) localErrors[EditedField.DATE] = ValidationError.EMPTY
        if (localErrors.isNotEmpty()) {
            showErrors(FieldErrors(localErrors))
            return
        }
        val at = form.date?.atTime(NOON)?.atZone(zone)?.toInstant()
        val target = editor.target
        viewModelScope.launch {
            if (target == null) {
                val result = transactions.addManual(ManualTransactionInput(form.type, amount, at, form.merchant))
                when (result) {
                    is Outcome.Ok -> local.update { it.copy(dialog = null, notice = TxnNotice(NoticeKind.ADDED)) }
                    is Outcome.Err -> showErrors(result.error)
                }
                return@launch
            }
            val dateChanged = form.date != target.at.atZone(zone).toLocalDate()
            val edit = TransactionEdit(
                type = form.type.takeIf { it != target.type },
                amount = amount.takeIf { it != target.amount },
                at = at.takeIf { dateChanged },
                merchant = form.merchant.takeIf { it.trim() != target.merchantName.orEmpty() },
            )
            if (edit.isEmpty) {
                local.update { it.copy(dialog = null) }
                return@launch
            }
            when (val result = transactions.edit(target.id, edit)) {
                is Outcome.Ok -> local.update { it.copy(dialog = null, notice = TxnNotice(NoticeKind.SAVED)) }
                is Outcome.Err -> when (val failure = result.error) {
                    is EditFailure.Invalid -> showErrors(failure.errors)
                    EditFailure.NotFound -> local.update { it.copy(dialog = null, notice = TxnNotice(NoticeKind.NOT_FOUND)) }
                }
            }
        }
    }

    private fun showErrors(errors: FieldErrors<EditedField>) = local.update { l ->
        val editor = l.dialog as? TransactionsDialog.Editor
        if (editor == null) l else l.copy(dialog = editor.copy(errors = errors))
    }

    // Merge

    fun startMerge() = local.update { it.copy(selecting = true, selected = emptyList(), notice = null) }

    fun cancelMerge() = local.update { it.copy(selecting = false, selected = emptyList()) }

    fun toggleSelected(id: TransactionId) = local.update { l ->
        val next = if (id in l.selected) {
            l.selected - id
        } else if (l.selected.size < MERGE_COUNT) {
            l.selected + id
        } else {
            l.selected
        }
        l.copy(selected = next)
    }

    /** Opens the merge dialog for the two selected Transactions, or says why they cannot merge (Requirement 32.8). */
    fun requestMerge() {
        val items = state.value.items
        val picked = local.value.selected.mapNotNull { id -> items.firstOrNull { it.id == id } }.sortedBy { it.id.value }
        if (picked.size != MERGE_COUNT) return
        val (first, second) = picked
        val error = TransactionMerge.check(first.type, second.type)
        if (error != null) {
            local.update { it.copy(selecting = false, selected = emptyList(), notice = noticeFor(error)) }
        } else {
            local.update { it.copy(dialog = TransactionsDialog.Merge(first, second)) }
        }
    }

    fun confirmMerge(selection: MergeSelection) {
        val dialog = local.value.dialog as? TransactionsDialog.Merge ?: return
        viewModelScope.launch {
            val notice = when (val result = transactions.merge(dialog.first.id, dialog.second.id, selection)) {
                is Outcome.Ok -> TxnNotice(NoticeKind.MERGED)
                is Outcome.Err -> noticeFor(result.error)
            }
            local.update { it.copy(dialog = null, selecting = false, selected = emptyList(), notice = notice) }
        }
    }

    private fun noticeFor(error: MergeError): TxnNotice = when (error) {
        MergeError.NOT_FOUND -> TxnNotice(NoticeKind.NOT_FOUND)
        MergeError.SAME_TRANSACTION -> TxnNotice(NoticeKind.MERGE_SAME)
        MergeError.DIFFERENT_TYPE -> TxnNotice(NoticeKind.MERGE_DIFFERENT_TYPE)
    }

    // Delete

    fun requestDelete(target: Transaction) = local.update { it.copy(dialog = TransactionsDialog.ConfirmDelete(target)) }

    fun confirmDelete() {
        val dialog = local.value.dialog as? TransactionsDialog.ConfirmDelete ?: return
        viewModelScope.launch {
            transactions.delete(dialog.target.id)
            local.update { it.copy(dialog = null, notice = TxnNotice(NoticeKind.DELETED)) }
        }
    }

    // Category

    fun requestCategory(target: Transaction) = local.update { it.copy(dialog = TransactionsDialog.PickCategory(target)) }

    fun chooseCategory(target: Transaction, category: CategoryId) {
        viewModelScope.launch {
            when (val result = transactions.changeCategory(target.id, category)) {
                is Outcome.Ok -> {
                    val offer = result.value
                    local.update {
                        it.copy(
                            dialog = offer?.let { o -> TransactionsDialog.Offer(o, categoryNameOf(o.category)) },
                            notice = TxnNotice(NoticeKind.CATEGORY_CHANGED),
                        )
                    }
                }
                is Outcome.Err -> {
                    val kind = if (result.error == CategoryChangeError.NOT_FOUND) NoticeKind.NOT_FOUND else NoticeKind.UNKNOWN_CATEGORY
                    local.update { it.copy(dialog = null, notice = TxnNotice(kind)) }
                }
            }
        }
    }

    fun applyOffer() {
        val dialog = local.value.dialog as? TransactionsDialog.Offer ?: return
        viewModelScope.launch {
            val changed = transactions.applyCategoryToEarlier(dialog.offer)
            local.update { it.copy(dialog = null, notice = TxnNotice(NoticeKind.CATEGORY_APPLIED, changed)) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MERGE_COUNT = 2
        val NOON: LocalTime = LocalTime.NOON
    }
}
