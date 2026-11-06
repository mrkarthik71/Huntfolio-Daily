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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.schedule.DayPlanner
import app.assistant.domain.engine.schedule.DayProposal
import app.assistant.domain.engine.schedule.PlanTask
import app.assistant.domain.engine.schedule.Span
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockKind
import app.assistant.domain.port.BodySignals
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.ReadoutRow
import app.assistant.ui.life.LifeSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * "Plan my day": a proposal of time blocks for today's open tasks in the free time left. Nothing
 * changes until the User taps Accept (CLAUDE.md: changes only after the User accepts a proposal).
 */
@HiltViewModel
@Suppress("LongParameterList") // reads tasks, blocks, calendar, routines, settings and sleep
class PlanDayViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val calendar: CalendarRepository,
    private val planner: PlannerRepository,
    private val settings: SettingsStore,
    private val body: BodySignals,
    private val time: TimeSource,
) : ViewModel() {
    private val _proposal = MutableStateFlow<DayProposal?>(null)
    val proposal: StateFlow<DayProposal?> = _proposal.asStateFlow()
    private val _accepted = MutableStateFlow(false)
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

    fun build() {
        viewModelScope.launch { _proposal.value = runCatching { compute() }.getOrNull() }
    }

    private suspend fun compute(): DayProposal {
        val zone = time.zone()
        val today = time.today()
        fun minute(i: Instant): Int {
            val z = i.atZone(zone)
            return when {
                z.toLocalDate().isBefore(today) -> 0
                z.toLocalDate().isAfter(today) -> MINUTES_PER_DAY
                else -> z.toLocalTime().toSecondOfDay() / SECONDS
            }
        }
        val blocks = plans.timeBlocks(today)
        val blocked = blocks.mapNotNull { it.taskId?.value }.toSet()
        val candidates = tasks.openTasks().first().filter { t ->
            t.status == TaskStatus.OPEN && t.id.value !in blocked &&
                (t.plannedDate == today || (t.plannedDate != null && t.plannedDate.isBefore(today)) || (t.dueDate != null && !t.dueDate.isAfter(today.plusDays(2))))
        }.map { PlanTask(it.id.value, it.title, it.estimateMinutes ?: DayPlanner.DEFAULT_MINUTES, it.priority, it.energy, it.dueDate) }
        val meetings = runCatching { calendar.eventsOn(today).first().timed.mapNotNull { f -> f.timed } }.getOrDefault(emptyList())
        val routines = runCatching { planner.routines().first() }.getOrDefault(emptyList()).filter { it.active && today.dayOfWeek in it.days }
        val busy = meetings.map { Span(minute(it.start), maxOf(minute(it.start), minute(it.end))) } +
            blocks.map { Span(minute(it.range.start), maxOf(minute(it.range.start), minute(it.range.end))) } +
            routines.map { r -> (r.start.toSecondOfDay() / SECONDS).let { Span(it, (it + r.durationMinutes).coerceAtMost(MINUTES_PER_DAY)) } }
        val wh = settings.current().workingHours
        val working = if (wh.spansMidnight) Span(wh.start.toSecondOfDay() / SECONDS, MINUTES_PER_DAY) else Span.of(wh.start, wh.end)
        val sleep = runCatching { if (body.connected()) body.lastNightSleepMinutes() else null }.getOrNull()
        return DayPlanner.propose(candidates, today, minute(time.now()), working, busy, sleep)
    }

    fun accept() {
        val p = _proposal.value ?: return
        viewModelScope.launch {
            val zone = time.zone()
            val today = time.today()
            plans.addBlocks(today, p.blocks.map { b -> TimeBlock(null, TaskId(b.taskId), InstantRange(at(today, b.span.start, zone), at(today, b.span.end, zone)), TimeBlockKind.FOCUS) })
            _accepted.value = true
        }
    }

    private fun at(day: LocalDate, minute: Int, zone: ZoneId): Instant = day.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()

    private companion object {
        const val SECONDS = 60
        const val MINUTES_PER_DAY = 24 * 60
    }
}

private fun hm(m: Int) = "%02d:%02d".format(m / MIN_PER_HOUR, m % MIN_PER_HOUR)

private const val MIN_PER_HOUR = 60

@Composable
fun PlanDaySheet(onDismiss: () -> Unit, viewModel: PlanDayViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.build() }
    val p by viewModel.proposal.collectAsStateWithLifecycle()
    val accepted by viewModel.accepted.collectAsStateWithLifecycle()
    LifeSheet(stringResource(R.string.planday_title), onDismiss) {
        val proposal = p
        when {
            proposal == null -> Text(stringResource(R.string.planday_working), color = MaterialTheme.colorScheme.onSurfaceVariant)
            accepted -> {
                Caption(stringResource(R.string.planday_done), color = MaterialTheme.colorScheme.tertiary)
                CredPrimaryButton(stringResource(R.string.share_done), onClick = onDismiss)
            }
            proposal.blocks.isEmpty() -> {
                Text(stringResource(R.string.planday_nothing), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.life_cancel)) }
            }
            else -> {
                if (proposal.lowSleep) Caption(stringResource(R.string.planday_low_sleep), color = MaterialTheme.colorScheme.primary, live = true)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    proposal.blocks.forEach { b -> ReadoutRow(hm(b.span.start), b.title, marker = EnergyColors.of(b.energy), trailing = hm(b.span.end)) }
                }
                if (proposal.unplaced.isNotEmpty()) {
                    Text(stringResource(R.string.planday_unplaced, proposal.unplaced.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CredPrimaryButton(stringResource(R.string.planday_accept), onClick = viewModel::accept)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) { TextButton(onClick = onDismiss) { Text(stringResource(R.string.life_cancel)) } }
            }
        }
    }
}
