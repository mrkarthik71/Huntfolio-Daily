package app.assistant.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.assistant.domain.engine.schedule.PaceRules
import app.assistant.domain.engine.schedule.PaceSample
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.ReflectionStore
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.Dial
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.life.DatePickDialog
import app.assistant.ui.life.LifeField
import app.assistant.ui.life.LifeSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Learns the User's pace from focus sessions on finished tasks (see [PaceRules]). */
@Singleton
class PaceReader @Inject constructor(private val reflection: ReflectionStore, private val tasks: TaskRepository, private val time: TimeSource) {
    suspend fun factor(): Double? = runCatching {
        val now = time.now()
        val sessions = reflection.sessions(now.minus(Duration.ofDays(WINDOW_DAYS)), now).first().filter { it.taskId != null && it.endedAt != null }
        val byTask = sessions.groupBy { it.taskId!! }.mapValues { (_, l) -> l.sumOf { Duration.between(it.startedAt, it.endedAt).toMinutes().toInt() } }
        val samples = byTask.mapNotNull { (id, actual) ->
            val t = tasks.get(app.assistant.domain.model.TaskId(id)) ?: return@mapNotNull null
            val est = t.estimateMinutes ?: return@mapNotNull null
            if (t.status != TaskStatus.DONE) null else PaceSample(est, actual)
        }
        PaceRules.factor(samples)
    }.getOrNull()

    private companion object {
        const val WINDOW_DAYS = 60L
    }
}

/** One goal plan as the Plans screen shows it. */
data class PlanCard(val goal: Goal, val done: Int, val total: Int, val missed: Int, val steps: List<Task>, val finish: LocalDate?) {
    val atRisk: Boolean get() = goal.deadline != null && finish != null && finish.isAfter(goal.deadline)
}

data class PlansUi(val cards: List<PlanCard> = emptyList(), val pace: Double? = null, val loading: Boolean = true)

@HiltViewModel
@Suppress("TooManyFunctions") // one small action per edit the screen offers
class PlansViewModel @Inject constructor(
    private val goals: GoalRepository,
    private val tasks: TaskRepository,
    private val planner: PlannerRepository,
    private val pace: PaceReader,
    private val time: TimeSource,
) : ViewModel() {
    private val _ui = MutableStateFlow(PlansUi())
    val ui: StateFlow<PlansUi> = _ui.asStateFlow()
    val today: LocalDate get() = time.today()

    init {
        viewModelScope.launch {
            val factor = pace.factor()
            combine(goals.statuses(time.today()), planner.goalWork(), tasks.openTasks()) { statuses, work, open ->
                val today = time.today()
                statuses.map { it.goal }.filter { it.isActive && it.countsTasks }.mapNotNull { g ->
                    val mine = work.filter { it.goal == g.id }
                    if (mine.isEmpty()) return@mapNotNull null
                    val steps = open.filter {
                        it.goalId == g.id && it.status == TaskStatus.OPEN
                    }.sortedWith(compareBy({ it.plannedDate ?: LocalDate.MAX }, { it.id.value }))
                    PlanCard(
                        goal = g,
                        done = mine.count { it.done },
                        total = mine.size,
                        missed = steps.count { it.plannedDate?.isBefore(today) == true },
                        steps = steps,
                        finish = steps.mapNotNull { it.plannedDate }.maxOrNull(),
                    )
                }.sortedBy { it.goal.priority }
            }.collect { cards -> _ui.value = PlansUi(cards, factor, loading = false) }
        }
    }

    fun done(t: Task) = launch { tasks.markDone(t.id, time.now(), time.today()) }

    fun move(t: Task, to: LocalDate) = launch { tasks.move(t.id, to, time.today()) }

    /** Swaps a step's day with its neighbour's, so the order changes without moving anything else. */
    fun swap(a: Task, b: Task) = launch {
        val today = time.today()
        val da = a.plannedDate ?: return@launch
        val db = b.plannedDate ?: return@launch
        if (da == db) return@launch
        if (!da.isBefore(today) && !db.isBefore(today)) {
            tasks.move(a.id, db, today)
            tasks.move(b.id, da, today)
        }
    }

    fun edit(t: Task, title: String, minutes: Int?) = launch {
        if (title.isBlank()) return@launch
        tasks.save(
            TaskInput(title.trim(), t.dueDate, t.plannedDate, minutes?.toString().orEmpty(), t.goalId),
            existing = t.id,
            now = time.now(),
            today = time.today(),
        )
    }

    fun drop(t: Task) = launch { tasks.drop(t.id) }

    fun addStep(goal: GoalId, title: String, minutes: Int?, on: LocalDate) = launch {
        if (title.isBlank()) return@launch
        tasks.save(
            TaskInput(title.trim(), plannedDate = on, estimateText = minutes?.toString().orEmpty(), goalId = goal),
            now = time.now(),
            today = time.today(),
        )
    }

    /** Ends a plan: the goal is archived and its open steps are dropped (finished ones stay in history). */
    fun endPlan(card: PlanCard) = launch {
        card.steps.forEach { tasks.drop(it.id) }
        goals.archive(card.goal.id, time.now())
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}

@Composable
fun PlansScreen(onBack: () -> Unit, onNewPlan: () -> Unit, onWeek: () -> Unit, viewModel: PlansViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var moving by remember { mutableStateOf<Task?>(null) }
    var adding by remember { mutableStateOf<PlanCard?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.plans_caption), Modifier.padding(start = 14.dp).weight(1f), color = MaterialTheme.colorScheme.primary)
                KeyButton(Icons.Filled.Add, stringResource(R.string.horizon_open), onClick = onNewPlan, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(
                stringResource(R.string.plans_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.enterAnimated(0),
            )
        }
        item { CheckIn(ui, onWeek) }
        if (!ui.loading && ui.cards.isEmpty()) {
            item {
                Text(stringResource(R.string.plans_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { CredPrimaryButton(stringResource(R.string.horizon_open), onClick = onNewPlan) }
        }
        items(ui.cards, key = { it.goal.id.value }) { card ->
            PlanCardView(
                card,
                expanded = open == card.goal.id.value,
                onToggle = { open = if (open == card.goal.id.value) null else card.goal.id.value },
                onEdit = { editing = it },
                onMove = { moving = it },
                onDone = viewModel::done,
                onSwap = viewModel::swap,
                onAdd = { adding = card },
                onEnd = { viewModel.endPlan(card) },
            )
        }
    }
    editing?.let { t ->
        var title by rememberSaveable(t.id.value) { mutableStateOf(t.title) }
        var minutes by rememberSaveable(t.id.value) { mutableStateOf(t.estimateMinutes?.toString().orEmpty()) }
        LifeSheet(stringResource(R.string.plans_edit_step), { editing = null }) {
            LifeField(title, { title = it }, stringResource(R.string.plans_step_title))
            LifeField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, stringResource(R.string.plans_step_minutes), number = true)
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                viewModel.edit(t, title, minutes.toIntOrNull())
                editing = null
            }, enabled = title.isNotBlank())
            TextButton(onClick = {
                viewModel.drop(t)
                editing = null
            }) { Text(stringResource(R.string.plans_remove_step), color = MaterialTheme.colorScheme.error) }
        }
    }
    moving?.let { t ->
        DatePickDialog(maxOf(t.plannedDate ?: viewModel.today, viewModel.today), onPick = {
            viewModel.move(t, it)
            moving = null
        }, onDismiss = { moving = null })
    }
    adding?.let { card ->
        var title by rememberSaveable { mutableStateOf("") }
        var minutes by rememberSaveable { mutableStateOf("30") }
        LifeSheet(stringResource(R.string.plans_add_step), { adding = null }) {
            LifeField(title, { title = it }, stringResource(R.string.plans_step_title))
            LifeField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, stringResource(R.string.plans_step_minutes), number = true)
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                val on = (card.finish ?: viewModel.today).let { if (it.isBefore(viewModel.today)) viewModel.today else it }
                viewModel.addStep(card.goal.id, title, minutes.toIntOrNull(), on)
                adding = null
            }, enabled = title.isNotBlank())
        }
    }
}

/** The weekly check-in: how the plans stand, and the learned pace. */
@Composable
private fun CheckIn(ui: PlansUi, onWeek: () -> Unit) {
    Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption(stringResource(R.string.plans_checkin), color = MaterialTheme.colorScheme.primary)
        val missed = ui.cards.sumOf { it.missed }
        val risky = ui.cards.count { it.atRisk }
        Text(
            when {
                ui.cards.isEmpty() -> stringResource(R.string.plans_checkin_none)
                missed == 0 && risky == 0 -> stringResource(R.string.plans_checkin_good)
                else -> stringResource(R.string.plans_checkin_behind, missed, risky)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            ui.pace?.let { stringResource(R.string.plans_pace, PaceRules.percent(it)) } ?: stringResource(R.string.plans_pace_learning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PillButton(
            stringResource(R.string.week_button),
            onClick = onWeek,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
@Suppress("LongParameterList") // each step action is a separate callback
private fun PlanCardView(
    card: PlanCard,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: (Task) -> Unit,
    onMove: (Task) -> Unit,
    onDone: (Task) -> Unit,
    onSwap: (Task, Task) -> Unit,
    onAdd: () -> Unit,
    onEnd: () -> Unit,
) {
    val pct = if (card.total == 0) 0f else card.done.toFloat() / card.total
    Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Dial(pct, "${(pct * PERCENT).toInt()}%", null, size = 72.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(card.goal.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    stringResource(R.string.plans_progress, card.done, card.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                card.finish?.let {
                    Text(
                        stringResource(if (card.atRisk) R.string.plans_finish_late else R.string.plans_finish, formatDate(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (card.atRisk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (card.missed > 0) {
                    Text(
                        stringResource(R.string.horizon_today_missed, card.missed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (expanded) {
            card.steps.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onEdit(t) }) {
                        Text(t.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            listOfNotNull(t.plannedDate?.let(::formatDate), t.estimateMinutes?.let(::hoursLabel)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onMove(t) },
                        )
                    }
                    if (i > 0) KeyButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.plans_earlier), onClick = { onSwap(t, card.steps[i - 1]) })
                    if (i <
                        card.steps.lastIndex
                    ) {
                        KeyButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.plans_later), onClick = {
                            onSwap(
                                t,
                                card.steps[
                                    i +
                                        1,
                                ],
                            )
                        })
                    }
                    TextButton(onClick = { onDone(t) }) { Text(stringResource(R.string.horizon_mark_done)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAdd) { Text(stringResource(R.string.plans_add_step)) }
                TextButton(onClick = onEnd) { Text(stringResource(R.string.plans_end), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private const val PERCENT = 100
