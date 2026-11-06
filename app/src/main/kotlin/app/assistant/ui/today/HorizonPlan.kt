package app.assistant.ui.today

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.schedule.Horizon
import app.assistant.domain.engine.schedule.HorizonGoal
import app.assistant.domain.engine.schedule.HorizonPlan
import app.assistant.domain.engine.schedule.HorizonPlanner
import app.assistant.domain.engine.schedule.HorizonStep
import app.assistant.domain.engine.schedule.StepSize
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.port.AdviceItem
import app.assistant.domain.port.Advisor
import app.assistant.domain.port.BreakdownAdvice
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.ReviewFacts
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutRow
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.life.LifeField
import app.assistant.ui.life.OptionKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One goal row the User is typing. */
data class GoalDraft(val name: String = "", val hours: String = "")

/** Where the plan-ahead screen is. */
sealed interface HorizonStage {
    data object Editing : HorizonStage

    data object Thinking : HorizonStage

    data class Proposal(val plan: HorizonPlan, val aiSteps: Boolean, val summary: String?) : HorizonStage

    data class Saved(val goals: Int, val sessions: Int) : HorizonStage
}

/** The localised built-in step titles, used when AI is off. */
data class StepWords(val start: String, val work: String, val finish: String)

/**
 * "Plan my week, month or year". The User lists what they want finished; Gemini (when AI is on) breaks
 * each goal into steps; the engine spreads the work over the days. Nothing is saved until Accept, which
 * creates one goal per line and one dated task per session (CLAUDE.md: changes only after the User accepts).
 */
@HiltViewModel
class HorizonPlanViewModel @Inject constructor(
    private val goals: GoalRepository,
    private val tasks: TaskRepository,
    private val planner: PlannerRepository,
    private val advisor: Advisor,
    private val time: TimeSource,
) : ViewModel() {
    private val _stage = MutableStateFlow<HorizonStage>(HorizonStage.Editing)
    val stage: StateFlow<HorizonStage> = _stage.asStateFlow()

    fun back() {
        _stage.value = HorizonStage.Editing
    }

    @Suppress("LongParameterList") // every input is one choice on the form
    fun make(horizon: Horizon, drafts: List<GoalDraft>, daysPerWeek: Int, maxDailyMinutes: Int, words: StepWords) {
        val list = drafts.mapIndexedNotNull { i, d ->
            val name = d.name.trim().take(NAME_MAX)
            if (name.isEmpty()) {
                null
            } else {
                HorizonGoal(
                    "g${i + 1}",
                    name,
                    d.hours.trim().toIntOrNull()?.takeIf { it in 1..MAX_HOURS } ?: horizon.defaultHours,
                    i + 1,
                )
            }
        }
        if (list.isEmpty()) return
        _stage.value = HorizonStage.Thinking
        viewModelScope.launch {
            val facts = ReviewFacts(
                items = list.map { AdviceItem(it.key, it.name) },
                numbers = mapOf("window" to horizon.name, "days_per_week" to daysPerWeek.toString()) +
                    list.associate { "hours_${it.key}" to it.hours.toString() },
                summaryLine = list.joinToString(" ") { it.key },
            )
            val advice = runCatching { advisor.breakDownGoals(facts) }.getOrNull()
            val steps = (advice as? BreakdownAdvice.Steps)?.steps.orEmpty().mapNotNull { s ->
                val size = runCatching { StepSize.valueOf(s.size) }.getOrNull() ?: return@mapNotNull null
                HorizonStep(s.handle, s.title, size)
            }
            val plan = HorizonPlanner.plan(horizon, time.today(), list, steps, daysPerWeek, maxDailyMinutes) { g ->
                listOf(
                    HorizonStep(g.key, words.start.format(g.name), StepSize.S),
                    HorizonStep(g.key, words.work.format(g.name), StepSize.L),
                    HorizonStep(g.key, words.finish.format(g.name), StepSize.S),
                )
            }
            _stage.value = HorizonStage.Proposal(plan, steps.isNotEmpty(), (advice as? BreakdownAdvice.Steps)?.summary)
        }
    }

    fun accept() {
        val p = (_stage.value as? HorizonStage.Proposal)?.plan ?: return
        _stage.value = HorizonStage.Thinking
        viewModelScope.launch {
            val now = time.now()
            val today = time.today()
            var saved = 0
            var sessions = 0
            p.goals.forEachIndexed { i, g ->
                val mine = p.sessions.filter { it.goalKey == g.key }
                val id = when (val r = goals.save(GoalInput(g.name, period(p.horizon), p.start, mine.size.toString(), TASKS_UNIT))) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return@forEachIndexed
                }
                val deadline = listOfNotNull(p.end, g.finish).max()
                runCatching { planner.setGoalPlanning(id, deadline, (i + 1).coerceAtMost(MAX_PRIORITY)) }
                saved++
                mine.forEach { s ->
                    val title = "${g.name}: ${s.title}".take(TITLE_MAX)
                    if (tasks.save(
                            TaskInput(title, plannedDate = s.date, estimateText = s.minutes.toString(), goalId = id),
                            now = now,
                            today = today,
                        ) is Outcome.Ok
                    ) {
                        sessions++
                    }
                }
            }
            _stage.value = HorizonStage.Saved(saved, sessions)
        }
    }

    private fun period(h: Horizon) = when (h) {
        Horizon.WEEK -> GoalPeriod.WEEK
        Horizon.MONTH -> GoalPeriod.MONTH
        Horizon.YEAR -> GoalPeriod.YEAR
    }

    private companion object {
        const val NAME_MAX = 100
        const val TITLE_MAX = 200
        const val MAX_HOURS = 2_000
        const val MAX_PRIORITY = 5
        const val TASKS_UNIT = "tasks"
    }
}

internal fun hoursLabel(minutes: Int): String {
    val h = minutes / MINUTES
    val m = minutes % MINUTES
    return when {
        h == 0 -> "$m m"
        m == 0 -> "$h h"
        else -> "$h h $m m"
    }
}

private const val MINUTES = 60
private const val MAX_GOALS = 8
private val DAILY_CHOICES = listOf(60, 120, 180, 240)
private val DAYS_CHOICES = listOf(5, 6, 7)

@Composable
fun HorizonPlanScreen(onBack: () -> Unit, viewModel: HorizonPlanViewModel = hiltViewModel()) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    var horizon by rememberSaveable { mutableStateOf(Horizon.WEEK) }
    var days by rememberSaveable { mutableStateOf(6) }
    var daily by rememberSaveable { mutableStateOf(120) }
    val drafts = remember { mutableStateListOf(GoalDraft(), GoalDraft()) }
    val words = StepWords(stringResource(R.string.horizon_step_start), stringResource(R.string.horizon_step_work), stringResource(R.string.horizon_step_finish))
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.horizon_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        when (val s = stage) {
            HorizonStage.Editing -> editing(horizon, { horizon = it }, days, { days = it }, daily, { daily = it }, drafts) {
                viewModel.make(horizon, drafts.toList(), days, daily, words)
            }
            HorizonStage.Thinking -> item {
                Caption(stringResource(R.string.horizon_thinking), color = MaterialTheme.colorScheme.primary, live = true)
            }
            is HorizonStage.Proposal -> proposal(s, onAccept = viewModel::accept, onEdit = viewModel::back)
            is HorizonStage.Saved -> item {
                Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.horizon_saved_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.horizon_saved_body, s.goals, s.sessions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CredPrimaryButton(stringResource(R.string.share_done), onClick = onBack)
                }
            }
        }
    }
}

@Suppress("LongParameterList") // the form's state is hoisted to the screen
private fun androidx.compose.foundation.lazy.LazyListScope.editing(
    horizon: Horizon,
    onHorizon: (Horizon) -> Unit,
    days: Int,
    onDays: (Int) -> Unit,
    daily: Int,
    onDaily: (Int) -> Unit,
    drafts: MutableList<GoalDraft>,
    onMake: () -> Unit,
) {
    item {
        Column(Modifier.enterAnimated(0)) {
            Text(stringResource(R.string.horizon_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.horizon_reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionKey(stringResource(R.string.horizon_week), horizon == Horizon.WEEK, { onHorizon(Horizon.WEEK) })
            OptionKey(stringResource(R.string.horizon_month), horizon == Horizon.MONTH, { onHorizon(Horizon.MONTH) })
            OptionKey(stringResource(R.string.horizon_year), horizon == Horizon.YEAR, { onHorizon(Horizon.YEAR) })
        }
    }
    item { Caption(stringResource(R.string.horizon_goals_caption)) }
    itemsIndexed(drafts) { i, d ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LifeField(d.name, { drafts[i] = d.copy(name = it) }, stringResource(R.string.horizon_goal_name, i + 1), Modifier.weight(2f))
            LifeField(
                d.hours,
                { v -> drafts[i] = d.copy(hours = v.filter(Char::isDigit).take(4)) },
                stringResource(R.string.horizon_goal_hours, horizon.defaultHours),
                Modifier.weight(1f),
                number = true,
            )
            if (drafts.size > 1) KeyButton(Icons.Filled.Close, stringResource(R.string.life_delete), onClick = { drafts.removeAt(i) })
        }
    }
    if (drafts.size < MAX_GOALS) {
        item {
            TextButton(onClick = { drafts += GoalDraft() }) {
                androidx.compose.material3.Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.horizon_add_goal))
            }
        }
    }
    item { Caption(stringResource(R.string.horizon_days_caption)) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAYS_CHOICES.forEach { n -> OptionKey(stringResource(R.string.horizon_days, n), days == n, { onDays(n) }) }
        }
    }
    item { Caption(stringResource(R.string.horizon_daily_caption)) }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAILY_CHOICES.forEach { m -> OptionKey(hoursLabel(m), daily == m, { onDaily(m) }) }
        }
    }
    item {
        CredPrimaryButton(stringResource(R.string.horizon_make), onClick = onMake, enabled = drafts.any { it.name.isNotBlank() })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.proposal(s: HorizonStage.Proposal, onAccept: () -> Unit, onEdit: () -> Unit) {
    val p = s.plan
    item {
        Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(
                stringResource(if (p.feasible) R.string.horizon_fits else R.string.horizon_too_much),
                color = if (p.feasible) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.horizon_daily_line, hoursLabel(p.dailyMinutes), p.daysPerWeek, p.finish?.let(::formatDate).orEmpty()),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (p.feasible) {
                    stringResource(R.string.horizon_total_line, hoursLabel(p.totalMinutes), formatDate(p.end))
                } else {
                    stringResource(R.string.horizon_needs_line, hoursLabel(p.neededDailyMinutes), formatDate(p.end))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            Text(
                stringResource(if (s.aiSteps) R.string.horizon_steps_ai else R.string.horizon_steps_rules),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { Caption(stringResource(R.string.horizon_per_goal)) }
    p.goals.forEach { g ->
        item(key = "goal-" + g.key) {
            ReadoutRow(
                hoursLabel(g.totalMinutes),
                g.name,
                trailing = g.finish?.let { stringResource(R.string.horizon_done_by, formatDate(it)) },
            )
        }
    }
    val byDay = p.sessions.groupBy { it.date }.toList().take(SHOWN_DAYS)
    item { Caption(stringResource(R.string.horizon_days_ahead, byDay.size)) }
    byDay.forEach { (date, list) ->
        item(key = "day-$date") {
            Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.medium).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${formatDate(date)} · ${hoursLabel(list.sumOf { it.minutes })}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                list.forEach { s2 ->
                    val goal = p.goals.firstOrNull { it.key == s2.goalKey }?.name.orEmpty()
                    Text(
                        "${hoursLabel(s2.minutes)} · $goal: ${s2.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CredPrimaryButton(stringResource(R.string.horizon_accept), onClick = onAccept)
            TextButton(onClick = onEdit) { Text(stringResource(R.string.horizon_edit)) }
        }
    }
}

private const val SHOWN_DAYS = 14
