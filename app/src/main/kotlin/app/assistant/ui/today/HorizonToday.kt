package app.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.schedule.HorizonPlanner
import app.assistant.domain.engine.schedule.MissedSession
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.raisedPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Today's steps from the User's week/month/year plans, and the steps missed on earlier days. */
data class PlanToday(val today: List<Task> = emptyList(), val missed: List<Task> = emptyList()) {
    val isEmpty: Boolean get() = today.isEmpty() && missed.isEmpty()
}

@HiltViewModel
class HorizonTodayViewModel @Inject constructor(private val tasks: TaskRepository, private val goals: GoalRepository, private val time: TimeSource) :
    ViewModel() {
    private val today: LocalDate = time.today()

    val state: StateFlow<PlanToday> = combine(tasks.plannedOn(today), tasks.openTasks()) { planned, open ->
        PlanToday(
            today = planned.filter { it.goalId != null && it.status != TaskStatus.DROPPED },
            missed = open.filter { it.goalId != null && it.status == TaskStatus.OPEN && it.plannedDate?.isBefore(today) == true },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), PlanToday())

    fun done(task: Task) {
        viewModelScope.launch { tasks.markDone(task.id, time.now(), time.today()) }
    }

    /** Moves missed steps onto the coming days with room, keeping each before its goal's deadline. */
    fun catchUp() {
        viewModelScope.launch {
            val now = time.today()
            val open = tasks.openTasks().first().filter { it.goalId != null && it.status == TaskStatus.OPEN }
            val future = open.filter { it.plannedDate != null && !it.plannedDate.isBefore(now) }
            val load = future.groupBy { it.plannedDate!! }.mapValues { (_, l) -> l.sumOf { it.estimateMinutes ?: HorizonPlanner.SLOT } }
            // Keep to the pace the plan already set: its busiest day, and the weekdays it uses.
            val daily = (load.values.maxOrNull() ?: HorizonPlanner.DEFAULT_DAILY_CAP).coerceAtLeast(HorizonPlanner.SLOT * 4)
            val days = (future.mapNotNull { it.plannedDate?.dayOfWeek?.value }.maxOrNull() ?: WEEK).coerceIn(WORK_WEEK, WEEK)
            val deadlines = open.mapNotNull { it.goalId }.distinct().associateWith { id -> runCatching { goals.get(id)?.deadline }.getOrNull() }
            val missed = open.filter { it.plannedDate?.isBefore(now) == true }
                .map { MissedSession(it.id.value, it.estimateMinutes ?: HorizonPlanner.SLOT, deadlines[it.goalId]) }
            HorizonPlanner.catchUp(missed, load, now, days, daily).forEach { (id, date) ->
                runCatching { tasks.move(app.assistant.domain.model.TaskId(id), date, now) }
            }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val WEEK = 7
        const val WORK_WEEK = 5
    }
}

/** The Today panel: "Today for your plan", with a Done button per step and Catch up when steps were missed. */
@Composable
fun HorizonTodayPanel(onOpenPlanner: () -> Unit, viewModel: HorizonTodayViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Caption(stringResource(R.string.horizon_today_caption), color = MaterialTheme.colorScheme.primary)
        if (s.isEmpty) {
            Text(stringResource(R.string.horizon_today_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val left = s.today.filter { it.status == TaskStatus.OPEN }
            Text(
                stringResource(R.string.horizon_today_summary, left.size, hoursLabel(left.sumOf { it.estimateMinutes ?: 0 })),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            s.today.forEach { t -> StepRow(t, onDone = { viewModel.done(t) }) }
            if (s.missed.isNotEmpty()) {
                Text(
                    stringResource(R.string.horizon_today_missed, s.missed.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PillButton(stringResource(R.string.horizon_catch_up), onClick = viewModel::catchUp)
            }
        }
        PillButton(
            stringResource(R.string.horizon_open),
            onClick = onOpenPlanner,
            modifier = Modifier.fillMaxWidth(),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StepRow(task: Task, onDone: () -> Unit) {
    val done = task.status == TaskStatus.DONE
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            task.estimateMinutes?.let { Text(hoursLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!done) TextButton(onClick = onDone) { Text(stringResource(R.string.horizon_mark_done)) }
    }
}
