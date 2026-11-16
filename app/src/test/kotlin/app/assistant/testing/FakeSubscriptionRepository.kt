package app.assistant.testing

import app.assistant.domain.engine.subscriptions.SubscriptionSnapshot
import app.assistant.domain.engine.suggestions.ProposedChange
import app.assistant.domain.engine.suggestions.SuggestionSnapshot
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SubscriptionStatus
import app.assistant.domain.port.DueUsageCheck
import app.assistant.domain.port.ManualSubscriptionField
import app.assistant.domain.port.ManualSubscriptionInput
import app.assistant.domain.port.ManualSubscriptionValidator
import app.assistant.domain.port.SubscriptionRepository
import app.assistant.domain.port.SubscriptionView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** In-memory [SubscriptionRepository] for UI tests: confirm, reject, manual add and usage answers mutate lists. */
class FakeSubscriptionRepository(
    private val today: LocalDate,
    proposals: List<SubscriptionView> = emptyList(),
    active: List<SubscriptionView> = emptyList(),
    usage: List<DueUsageCheck> = emptyList(),
) : SubscriptionRepository {
    val proposalsState = MutableStateFlow(proposals)
    val activeState = MutableStateFlow(active)
    val usageState = MutableStateFlow(usage)
    val usageAnswers = mutableListOf<Pair<Long, Boolean>>()
    private val suggestionsState = MutableStateFlow<List<SuggestionSnapshot>>(emptyList())
    private var nextId = 1_000L

    override fun proposals(): Flow<List<SubscriptionView>> = proposalsState

    override fun active(): Flow<List<SubscriptionView>> = activeState.map { list -> list.sortedBy { it.snapshot.nextDue } }

    override fun totalYearlyCost(): Flow<Paise> = activeState.map { list -> Paise(list.sumOf { it.yearlyCost.value }) }

    override fun suggestions(): Flow<List<SuggestionSnapshot>> = suggestionsState

    override fun dueUsageChecks(): Flow<List<DueUsageCheck>> = usageState

    override suspend fun redetect() = Unit

    override suspend fun confirmProposal(subscriptionId: Long) {
        val sub = proposalsState.value.firstOrNull { it.snapshot.id == subscriptionId } ?: return
        proposalsState.value = proposalsState.value - sub
        activeState.value = activeState.value + sub.copy(snapshot = sub.snapshot.copy(status = SubscriptionStatus.ACTIVE))
    }

    override suspend fun rejectProposal(subscriptionId: Long) {
        proposalsState.value = proposalsState.value.filterNot { it.snapshot.id == subscriptionId }
    }

    override suspend fun addManual(input: ManualSubscriptionInput): Outcome<Unit, FieldErrors<ManualSubscriptionField>> {
        val errors = ManualSubscriptionValidator.validate(input, today)
        if (!errors.isValid) return Outcome.Err(errors)
        activeState.value = activeState.value + view(nextId++, input.merchant.trim(), input.amount, input.interval, input.nextDue, today)
        return Outcome.Ok(Unit)
    }

    override suspend fun answerUsageCheck(subscriptionId: Long, usedIt: Boolean) {
        usageAnswers += subscriptionId to usedIt
        usageState.value = usageState.value.filterNot { it.subscriptionId == subscriptionId }
    }

    override suspend fun markCancelled(subscriptionId: Long) = Unit

    override suspend fun acceptSuggestion(suggestionId: Long) = Unit

    override suspend fun dismissSuggestion(suggestionId: Long) = Unit

    override suspend fun applyChange(change: ProposedChange) = Unit

    override suspend fun runDailyMaintenance() = Unit

    companion object {
        private const val WEEKS = 52L
        private const val QUARTERS = 4L
        private const val MONTHS = 12L

        fun view(
            id: Long,
            merchant: String,
            amount: Paise,
            interval: SubscriptionInterval,
            nextDue: LocalDate,
            activatedOn: LocalDate,
            status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        ): SubscriptionView {
            val perYear = when (interval) {
                SubscriptionInterval.WEEKLY -> WEEKS
                SubscriptionInterval.MONTHLY -> MONTHS
                SubscriptionInterval.QUARTERLY -> QUARTERS
                SubscriptionInterval.YEARLY -> 1L
            }
            val snapshot = SubscriptionSnapshot(
                id = id,
                merchantId = MerchantId(id),
                amount = amount,
                interval = interval,
                anchorDay = nextDue.dayOfMonth,
                nextDue = nextDue,
                activatedOn = activatedOn,
                lastUsageCheck = null,
                cancelledOn = null,
                status = status,
            )
            return SubscriptionView(snapshot, merchant, Paise(amount.value * perYear))
        }
    }
}
