package app.assistant.ui.money.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.port.SpendingReportRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class ReportMode { MONTHLY, WEEKLY }

/** Which report is shown: the mode and the period of each mode, so switching mode keeps the other period. */
data class ReportSelection(val mode: ReportMode, val month: YearMonth, val week: PeriodInstance)

data class SpendingReportsUi(
    val loading: Boolean = true,
    val selection: ReportSelection,
    val monthly: MonthlySpendingReport? = null,
    val weekly: WeeklySpendingReport? = null,
    /** Periods after the current one have nothing to show, so navigation stops there. */
    val canGoNext: Boolean = false,
    /** AI monthly-money wording, layered on after the rule-based report (Requirement 48.1); monthly mode only. */
    val aiSummary: String? = null,
    val aiSuggestions: List<AiReportSuggestion> = emptyList(),
    val aiUnavailable: Boolean = false,
) {
    /** True when the shown period has no spending and no Reversals (Requirement 35). */
    val isEmpty: Boolean
        get() = when (selection.mode) {
            ReportMode.MONTHLY -> monthly?.let { it.lines.isEmpty() && it.reversals.value == 0L } ?: false
            ReportMode.WEEKLY -> weekly?.let { it.top.isEmpty() && it.reversals.value == 0L } ?: false
        }
}

/** An AI-proposed budgeting observation from the monthly money review (shown, never applied). */
data class AiReportSuggestion(val text: String, val reason: String)

@HiltViewModel
class SpendingReportsViewModel @Inject constructor(
    private val reports: SpendingReportRepository,
    time: TimeSource,
    private val advisor: app.assistant.domain.port.Advisor,
) : ViewModel() {

    private val today: LocalDate = time.today()
    private val currentMonth: YearMonth = YearMonth.from(today)
    private val currentWeek: PeriodInstance = Periods.instanceContaining(GoalPeriod.WEEK, today, DayOfWeek.SUNDAY)
    private val selection = MutableStateFlow(ReportSelection(ReportMode.MONTHLY, currentMonth, currentWeek))
    private val _state = MutableStateFlow(SpendingReportsUi(selection = selection.value))
    val state: StateFlow<SpendingReportsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Open on the latest report that exists; the current period is the fallback before the first one is made.
            val latestMonth = reports.latestMonth().first()
            val latestWeek = reports.latestWeek().first()
            selection.value = ReportSelection(ReportMode.MONTHLY, latestMonth ?: currentMonth, latestWeek ?: currentWeek)
            selection.collectLatest { shown -> show(shown) }
        }
    }

    fun setMode(mode: ReportMode) {
        selection.value = selection.value.copy(mode = mode)
    }

    fun previous() {
        val shown = selection.value
        selection.value = when (shown.mode) {
            ReportMode.MONTHLY -> shown.copy(month = shown.month.minusMonths(1))
            ReportMode.WEEKLY -> shown.copy(week = Periods.weekEndingOn(shown.week.last.minusDays(WEEK_DAYS)))
        }
    }

    fun next() {
        val shown = selection.value
        if (!canGoNext(shown)) return
        selection.value = when (shown.mode) {
            ReportMode.MONTHLY -> shown.copy(month = shown.month.plusMonths(1))
            ReportMode.WEEKLY -> shown.copy(week = Periods.weekEndingOn(shown.week.last.plusDays(WEEK_DAYS)))
        }
    }

    private fun canGoNext(shown: ReportSelection): Boolean = when (shown.mode) {
        ReportMode.MONTHLY -> shown.month < currentMonth
        ReportMode.WEEKLY -> shown.week.last.isBefore(currentWeek.last)
    }

    private suspend fun show(shown: ReportSelection) {
        _state.value = SpendingReportsUi(loading = true, selection = shown, canGoNext = canGoNext(shown))
        _state.value = when (shown.mode) {
            ReportMode.MONTHLY -> SpendingReportsUi(false, shown, monthly = reports.monthly(shown.month), canGoNext = canGoNext(shown))
            ReportMode.WEEKLY -> SpendingReportsUi(false, shown, weekly = reports.weekly(shown.week), canGoNext = canGoNext(shown))
        }
    }

    private companion object {
        const val WEEK_DAYS = 7L
        const val RUPEE = 100
    }

    /** Requests AI wording for the monthly money review after the rule-based report is shown (Requirement 48.1). */
    fun requestAiWording() {
        val ui = _state.value
        if (ui.selection.mode != ReportMode.MONTHLY) return
        val monthly = ui.monthly ?: return
        viewModelScope.launch {
            val items = monthly.lines.mapIndexed { i, line -> app.assistant.domain.port.AdviceItem("c${i + 1}", line.name) }
            val numbers = buildMap {
                put("total", (monthly.total.value / RUPEE).toString())
                monthly.lines.forEachIndexed { i, line -> put("c${i + 1}_amount", (line.amount.value / RUPEE).toString()) }
            }
            val facts = app.assistant.domain.port.ReviewFacts(items = items, numbers = numbers)
            when (val r = advisor.review(app.assistant.domain.model.PromptType.MONTHLY_MONEY_REVIEW, facts)) {
                is app.assistant.domain.port.ReviewAdvice.Wording -> {
                    val suggestions = r.suggestions.map { AiReportSuggestion(it.text, it.reason) }
                    _state.value = _state.value.copy(aiSummary = r.summary, aiSuggestions = suggestions, aiUnavailable = false)
                }
                is app.assistant.domain.port.ReviewAdvice.Unavailable ->
                    _state.value = _state.value.copy(aiUnavailable = r.reason != app.assistant.domain.port.AdviceUnavailable.DISABLED)
            }
        }
    }
}
