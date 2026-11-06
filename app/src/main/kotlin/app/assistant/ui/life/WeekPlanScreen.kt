package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.schedule.DayPlanner
import app.assistant.domain.engine.schedule.PlanTask
import app.assistant.domain.engine.schedule.WeekPlanner
import app.assistant.domain.engine.schedule.WeekProposal
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.designsystem.recessedWell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class WeekPlanViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val goals: GoalRepository,
    private val time: TimeSource,
    notify: NotifySwitch,
) : LifeViewModel(notify, NotificationType.WEEKLY_PLAN) {
    private val _proposal = MutableStateFlow<WeekProposal?>(null)
    val proposal: StateFlow<WeekProposal?> = _proposal.asStateFlow()
    private val _accepted = MutableStateFlow(false)
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

    init {
        viewModelScope.launch {
            val today = time.today()
            val open = runCatching { tasks.openTasks().first() }.getOrDefault(emptyList()).filter { it.status == TaskStatus.OPEN }
                .map { PlanTask(it.id.value, it.title, it.estimateMinutes ?: DayPlanner.DEFAULT_MINUTES, it.priority, it.energy, it.dueDate) }
            val behind = runCatching { goals.statusesNow(today) }.getOrDefault(emptyList()).filter { it.isBehind }.map { it.goal.name }
            _proposal.value = WeekPlanner.propose(open, today, behind)
        }
    }

    /** Puts each chosen task on its day. Nothing moves before the User accepts. */
    fun accept() {
        val p = _proposal.value ?: return
        viewModelScope.launch {
            val today = time.today()
            p.top.forEach { s -> tasks.move(TaskId(s.task.id), s.day, today) }
            _accepted.value = true
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun WeekPlanScreen(onBack: () -> Unit, viewModel: WeekPlanViewModel = hiltViewModel()) {
    val p by viewModel.proposal.collectAsStateWithLifecycle()
    val accepted by viewModel.accepted.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    WeekPlanContent(p, accepted, notify, viewModel::setNotify, viewModel::accept, onBack)
}

@Composable
@Suppress("LongParameterList") // state plus one callback per action
fun WeekPlanContent(p: WeekProposal?, accepted: Boolean, notify: Boolean, onNotify: (Boolean) -> Unit, onAccept: () -> Unit, onBack: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.week_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Column(Modifier.enterAnimated(0)) {
                Text(stringResource(R.string.week_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                p?.let {
                    Text(
                        stringResource(R.string.week_from, it.weekStart.format(dayFormat)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val top = p?.top.orEmpty()
        if (p != null &&
            top.isEmpty()
        ) {
            item {
                Text(stringResource(R.string.week_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(top, key = { _, s -> s.task.id }) { i, s ->
            Row(
                Modifier.fillMaxWidth().enterAnimated(i + 1).raisedPanel(MaterialTheme.shapes.large).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "${i + 1}",
                    style = app.assistant.ui.designsystem.AssistantType.Readout,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.recessedWell(MaterialTheme.shapes.small).padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(s.task.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Caption(s.day.format(dayFormat) + (s.task.due?.let { " · " + stringResource(R.string.week_due, it.format(dayFormat)) } ?: ""))
                }
            }
        }
        if (p != null && p.goalsBehind.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().recessedWell(MaterialTheme.shapes.medium).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(stringResource(R.string.week_goals_behind), color = MaterialTheme.colorScheme.primary, live = true)
                    p.goalsBehind.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground) }
                    Text(
                        stringResource(R.string.week_goals_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (top.isNotEmpty()) {
            item {
                if (accepted) {
                    Caption(stringResource(R.string.week_done), color = MaterialTheme.colorScheme.tertiary)
                } else {
                    CredPrimaryButton(stringResource(R.string.week_accept), onClick = onAccept)
                }
            }
        }
        item { NotifyToggle(notify, stringResource(R.string.week_notify), stringResource(R.string.week_notify_reason), onNotify) }
    }
}
