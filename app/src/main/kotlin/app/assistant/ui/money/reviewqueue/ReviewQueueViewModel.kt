package app.assistant.ui.money.reviewqueue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.Paise
import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.ReviewItem
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The open items, newest first, and how many there are (Requirements 30.7, 30.8). */
data class ReviewQueueUi(val loading: Boolean = true, val items: List<ReviewItem> = emptyList(), val count: Int = 0)

/** What the User typed for one item. A null type or date, or blank text, is a missing field. */
data class ReviewForm(val type: TxnType?, val amountText: String, val date: LocalDate?, val merchant: String)

/** Reads rupee text such as `1,234.50` into paise without any floating point. */
internal object ReviewAmount {
    private val PATTERN = Regex("^[0-9]{1,12}(\\.[0-9]{1,2})?$")

    /** Null when [text] is not a rupee amount with at most two decimals. A leading rupee sign and commas are ignored. */
    fun parse(text: String): Paise? {
        val clean = text.trim().removePrefix("₹").replace(",", "").trim()
        if (!PATTERN.matches(clean)) return null
        return Paise(BigDecimal(clean).movePointRight(2).longValueExact())
    }
}

@HiltViewModel
class ReviewQueueViewModel @Inject constructor(private val queue: ReviewQueueRepository, private val time: TimeSource) : ViewModel() {

    val state: StateFlow<ReviewQueueUi> = combine(queue.openItemsNewestFirst(), queue.openCount()) { items, count ->
        ReviewQueueUi(loading = false, items = items, count = count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReviewQueueUi())

    fun zone(): ZoneId = time.zone()

    /**
     * Turns the item into a Transaction. [onResult] gets null when it was saved (or is already gone) and the field errors
     * otherwise; an invalid save leaves the item queued (Requirements 30.3, 30.6).
     */
    fun save(id: ReviewItemId, form: ReviewForm, onResult: (FieldErrors<EditedField>?) -> Unit) {
        viewModelScope.launch {
            val amount = ReviewAmount.parse(form.amountText)
            val input = ManualTransactionInput(
                type = form.type,
                amount = amount,
                at = form.date?.atStartOfDay(time.zone())?.toInstant(),
                merchant = form.merchant,
            )
            val failure = when (val result = queue.resolve(id, input)) {
                is Outcome.Ok -> null
                is Outcome.Err -> result.error
            }
            val errors = (failure as? EditFailure.Invalid)?.errors
            onResult(if (errors == null) null else withAmountText(errors, form.amountText, amount))
        }
    }

    fun dismiss(id: ReviewItemId) {
        viewModelScope.launch { queue.dismiss(id) }
    }

    /** Text that is not a number is reported as such rather than as a missing amount. */
    private fun withAmountText(errors: FieldErrors<EditedField>, text: String, parsed: Paise?): FieldErrors<EditedField> =
        if (parsed == null && text.isNotBlank()) {
            FieldErrors(errors.errors + (EditedField.AMOUNT to ValidationError.NOT_A_NUMBER))
        } else {
            errors
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The number of open items, for the badge the Money host shows (Requirement 30.7). */
@HiltViewModel
class ReviewQueueCountViewModel @Inject constructor(queue: ReviewQueueRepository) : ViewModel() {
    val count: StateFlow<Int> = queue.openCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
