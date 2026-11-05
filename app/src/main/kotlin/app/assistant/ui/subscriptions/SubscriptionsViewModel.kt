package app.assistant.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.suggestions.SuggestionSnapshot
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.port.DueUsageCheck
import app.assistant.domain.port.ManualSubscriptionField
import app.assistant.domain.port.ManualSubscriptionInput
import app.assistant.domain.port.SubscriptionRepository
import app.assistant.domain.port.SubscriptionView
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Everything the Subscriptions screen shows (Requirement 37.1, 38.1, 38.3, 39.4, 50.1). */
data class SubscriptionsUi(
    val proposals: List<SubscriptionView> = emptyList(),
    val active: List<SubscriptionView> = emptyList(),
    val totalYearlyCost: Paise = Paise.ZERO,
    val dueUsageChecks: List<DueUsageCheck> = emptyList(),
    val suggestions: List<SuggestionSnapshot> = emptyList(),
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val repo: SubscriptionRepository,
    private val time: TimeSource,
) : ViewModel() {

    /** Today's date from the injected clock, the default next-due date for a manual Subscription. */
    fun today(): LocalDate = time.today()

    val ui: StateFlow<SubscriptionsUi> = combine(
        repo.proposals(),
        repo.active(),
        repo.totalYearlyCost(),
        repo.dueUsageChecks(),
        repo.suggestions(),
    ) { proposals, active, total, due, suggestions ->
        SubscriptionsUi(proposals, active, total, due, suggestions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUi())

    fun confirm(id: Long) = viewModelScope.launch { repo.confirmProposal(id) }

    fun reject(id: Long) = viewModelScope.launch { repo.rejectProposal(id) }

    fun answerUsage(id: Long, usedIt: Boolean) = viewModelScope.launch { repo.answerUsageCheck(id, usedIt) }

    fun acceptSuggestion(id: Long) = viewModelScope.launch { repo.acceptSuggestion(id) }

    fun dismissSuggestion(id: Long) = viewModelScope.launch { repo.dismissSuggestion(id) }

    /** Save a manual Subscription; invokes [onResult] with any field errors (empty errors = success). */
    fun addManual(merchant: String, amount: Paise, interval: SubscriptionInterval, nextDue: LocalDate, onResult: (FieldErrors<ManualSubscriptionField>) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.addManual(ManualSubscriptionInput(merchant, amount, interval, nextDue))) {
                is Outcome.Ok -> onResult(FieldErrors.none())
                is Outcome.Err -> onResult(r.error)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
