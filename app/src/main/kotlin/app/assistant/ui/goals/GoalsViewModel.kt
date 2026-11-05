package app.assistant.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.goals.GoalField
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.LinkError
import app.assistant.domain.engine.review.WeeklyProgressReport
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalStatus
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class GoalsUi(val loading: Boolean = true, val goals: List<GoalStatus> = emptyList(), val streak: Int = 0, val lastWeek: WeeklyProgressReport? = null)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goals: GoalRepository,
    private val plans: PlanRepository,
    private val reports: ReportRepository,
    private val time: TimeSource,
) : ViewModel() {

    private val today = time.today()

    val state: StateFlow<GoalsUi> = combine(
        goals.statuses(today),
        plans.streak(today),
        reports.latestWeek().flatMapLatest { week ->
            flow { emit(if (week == null) null else runCatching { reports.weeklyProgress(week) }.getOrNull()) }
        },
    ) { statuses, streak, report ->
        GoalsUi(loading = false, goals = statuses, streak = streak, lastWeek = report)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUi())

    suspend fun load(id: GoalId): Goal? = goals.get(id)

    fun save(input: GoalInput, existing: GoalId?, onResult: (FieldErrors<GoalField>?) -> Unit) {
        viewModelScope.launch {
            val result = goals.save(input, existing)
            onResult(if (result is Outcome.Err) result.error else null)
        }
    }

    fun logProgress(id: GoalId, amount: String, onResult: (ValidationError?) -> Unit) {
        viewModelScope.launch {
            val result = goals.logProgress(id, amount, time.today())
            onResult(if (result is Outcome.Err) result.error else null)
        }
    }

    fun link(child: GoalId, parent: GoalId?, onResult: (LinkError?) -> Unit) {
        viewModelScope.launch {
            val result = goals.link(child, parent)
            onResult(if (result is Outcome.Err) result.error else null)
        }
    }

    fun archive(id: GoalId) = viewModelScope.launch { goals.archive(id, time.now()) }

    fun today(): LocalDate = today
}
