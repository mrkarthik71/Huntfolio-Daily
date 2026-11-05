package app.assistant.ui.money.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.budget.BudgetField
import app.assistant.domain.engine.budget.BudgetStatus
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.errorOrNull
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.port.BudgetRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** A category the User can set a Budget for. */
data class BudgetCategory(val id: CategoryId, val name: String)

/** One Budget with what has been spent against it in the shown month. */
data class BudgetRow(val category: BudgetCategory, val amount: Paise, val status: BudgetStatus)

data class BudgetsUi(
    val loading: Boolean = true,
    val month: YearMonth,
    val rows: List<BudgetRow> = emptyList(),
    /** Categories that have no Budget yet. */
    val available: List<BudgetCategory> = emptyList(),
    /** Months after the current one have nothing to show, so navigation stops there. */
    val canGoNext: Boolean = false,
)

/** The built-in categories by id. An id with no name is shown as its number so nothing is dropped. */
object BudgetCategories {
    val all: List<BudgetCategory> = BuiltinCategories.NAMES.mapIndexed { index, name -> BudgetCategory(CategoryId(index + 1L), name) }

    fun of(id: CategoryId): BudgetCategory = all.firstOrNull { it.id == id } ?: BudgetCategory(id, "Category ${id.value}")
}

@HiltViewModel
class BudgetsViewModel @Inject constructor(private val budgets: BudgetRepository, time: TimeSource) : ViewModel() {

    private val current: YearMonth = YearMonth.from(time.today())
    private val month = MutableStateFlow(current)
    private val _state = MutableStateFlow(BudgetsUi(month = current))
    val state: StateFlow<BudgetsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            month.flatMapLatest { shown -> budgets.lines(shown).map { lines -> shown to lines } }
                .collect { (shown, lines) ->
                    val rows = lines.map { BudgetRow(BudgetCategories.of(it.budget.category), it.budget.amount, it.status) }
                    val taken = rows.map { it.category.id }.toSet()
                    _state.value = BudgetsUi(
                        loading = false,
                        month = shown,
                        rows = rows,
                        available = BudgetCategories.all.filter { it.id !in taken },
                        canGoNext = shown < current,
                    )
                }
        }
    }

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        if (month.value < current) month.value = month.value.plusMonths(1)
    }

    /** Stores the Budget, or reports the field errors and changes nothing (Requirement 33.6). [onResult] gets null on success. */
    fun save(category: CategoryId?, amountText: String, onResult: (FieldErrors<BudgetField>?) -> Unit) {
        val parsed = MoneyAmountText.parse(amountText)
        val amount = (parsed as? Outcome.Ok)?.value
        if (category == null || amount == null) {
            onResult(
                fieldErrors<BudgetField> {
                    if (category == null) add(BudgetField.CATEGORY, ValidationError.EMPTY)
                    parsed.errorOrNull()?.let { add(BudgetField.AMOUNT, it) }
                },
            )
            return
        }
        viewModelScope.launch {
            val result = budgets.save(category, amount)
            onResult(if (result is Outcome.Err) result.error else null)
        }
    }

    fun remove(category: CategoryId) {
        viewModelScope.launch { budgets.remove(category) }
    }
}
