package app.assistant.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.goals.QuantityParser
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalStatus
import app.assistant.ui.common.errorText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.assistant.domain.engine.schedule.GoalPlanView
import app.assistant.domain.engine.schedule.GoalPlanning
import app.assistant.domain.engine.schedule.ProposedMilestone
import app.assistant.ui.common.DateField
import app.assistant.ui.common.formatDate
import app.assistant.ui.common.formatShortDate
import app.assistant.ui.designsystem.ApexCard
import app.assistant.ui.designsystem.ApexProgressBar
import app.assistant.ui.designsystem.EmptyState
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.Eyebrow
import app.assistant.ui.designsystem.HeroCard
import app.assistant.ui.designsystem.MiniStat
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.ProgressRing
import app.assistant.ui.designsystem.SectionHeader
import app.assistant.ui.designsystem.TimelineRow
import app.assistant.ui.designsystem.enterAnimated

@Composable
fun GoalsScreen(viewModel: GoalsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<GoalEditorTarget?>(null) }
    var logging by rememberSaveable { mutableStateOf<GoalId?>(null) }
    var archiving by rememberSaveable { mutableStateOf<GoalId?>(null) }
    var planning by rememberSaveable { mutableStateOf<Long?>(null) }
    var scheduling by rememberSaveable { mutableStateOf<Long?>(null) }
    val atRisk = ui.plans.values.count { it.atRisk }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.goals_summary, ui.goals.size, atRisk),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.nav_goals), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                }
                FilledIconButton(
                    onClick = { editing = GoalEditorTarget.New },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onBackground, contentColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.goals_add)) }
            }
        }
        if (!ui.loading && ui.goals.isEmpty()) {
            item { EmptyState(stringResource(R.string.goals_empty)) }
        }
        val first = ui.goals.firstOrNull()
        if (first != null) {
            val plan = ui.plans[first.goal.id]
            item { GoalHero(first, plan, Modifier.enterAnimated(0)) }
            if (plan != null && plan.milestones.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.goal_milestones).uppercase()) }
                item { MilestoneList(plan, onToggle = { id, done -> viewModel.setMilestoneDone(id, done) }, modifier = Modifier.enterAnimated(1)) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.goal_plan_it), onClick = { planning = first.goal.id.value }, modifier = Modifier.weight(1f))
                    PillButton(
                        stringResource(R.string.goal_more),
                        onClick = { scheduling = first.goal.id.value },
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurface,
                        border = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        itemsIndexed(ui.goals.drop(1), key = { _, it -> it.goal.id.value }) { i, status ->
            GoalCard(
                status = status,
                plan = ui.plans[status.goal.id],
                onEdit = { editing = GoalEditorTarget.Existing(status.goal.id) },
                onLog = { logging = status.goal.id },
                onArchive = { archiving = status.goal.id },
                onPlan = { planning = status.goal.id.value },
                onSchedule = { scheduling = status.goal.id.value },
                modifier = Modifier.enterAnimated(i + 2),
            )
        }
        if (first != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { logging = first.goal.id }) { Text(stringResource(R.string.goal_log_progress)) }
                    TextButton(onClick = { editing = GoalEditorTarget.Existing(first.goal.id) }) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = { archiving = first.goal.id }) { Text(stringResource(R.string.goal_archive)) }
                }
            }
        }
        if (ui.streak > 0) {
            item { Text(stringResource(R.string.goals_streak, ui.streak), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        }
        ui.lastWeek?.let { report ->
            if (report.goals.isNotEmpty() || report.slipped.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.goals_last_week).uppercase()) }
                items(report.goals, key = { "w${it.name}" }) { row ->
                    Text(
                        stringResource(
                            R.string.goal_progress_line,
                            QuantityParser.format(row.progress),
                            QuantityParser.format(row.target),
                            row.unit,
                            app.assistant.domain.engine.goals.ProgressMath.percent(row.progress, row.target),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (report.slipped.isNotEmpty()) {
                    item { Text(stringResource(R.string.goals_slipped), style = MaterialTheme.typography.titleSmall) }
                    items(report.slipped, key = { "s${it.first}" }) { (name, moves) ->
                        Text(stringResource(R.string.goals_moves, name, moves), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    editing?.let { target ->
        GoalEditorSheet(
            target = target,
            allGoals = ui.goals.map { it.goal },
            defaultStart = viewModel.today(),
            load = { viewModel.load(it) },
            onSave = { input, existing, onErrors -> viewModel.save(input, existing, onErrors) },
            onLink = { child, parent, onErr -> viewModel.link(child, parent, onErr) },
            onDismiss = { editing = null },
        )
    }

    logging?.let { id ->
        LogProgressDialog(
            onConfirm = { amount, onErr -> viewModel.logProgress(id, amount, onErr) },
            onDone = { logging = null },
            onDismiss = { logging = null },
        )
    }

    archiving?.let { id ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text(stringResource(R.string.goal_archive_title)) },
            text = { Text(stringResource(R.string.goal_archive_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archive(id)
                    archiving = null
                }) { Text(stringResource(R.string.goal_archive)) }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text(stringResource(R.string.not_now)) } },
        )
    }

    planning?.let { raw ->
        val status = ui.goals.firstOrNull { it.goal.id.value == raw }
        if (status == null) {
            planning = null
        } else {
            PlanProposalSheet(
                status = status,
                propose = { titles -> viewModel.proposePlan(status, titles) },
                onAccept = { steps ->
                    viewModel.acceptPlan(status.goal.id, steps)
                    planning = null
                },
                onDismiss = { planning = null },
            )
        }
    }

    scheduling?.let { raw ->
        val status = ui.goals.firstOrNull { it.goal.id.value == raw }
        if (status == null) {
            scheduling = null
        } else {
            GoalPlanningDialog(
                status = status,
                onSave = { deadline, priority ->
                    viewModel.setGoalPlanning(status.goal.id, deadline, priority)
                    scheduling = null
                },
                onDismiss = { scheduling = null },
            )
        }
    }
}

/** The top goal: priority and deadline, a progress ring, and hours done, planned and forecast. */
@Composable
private fun GoalHero(status: GoalStatus, plan: GoalPlanView?, modifier: Modifier = Modifier) {
    val percent = plan?.percent ?: status.percent
    HeroCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow(
                        status.goal.deadline?.let { stringResource(R.string.goal_priority_due, status.goal.priority, formatDate(it)) }
                            ?: stringResource(R.string.goal_priority, status.goal.priority),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    )
                    Text(status.goal.name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (plan?.atRisk == true) RiskChip()
                }
                ProgressRing(percent / 100f, stringResource(R.string.percent, percent), size = 68.dp, track = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat(stringResource(R.string.goal_done), hours(plan?.minutesDone ?: 0), Modifier.weight(1f))
                MiniStat(stringResource(R.string.goal_planned), hours(plan?.minutesPlanned ?: 0), Modifier.weight(1f))
                MiniStat(stringResource(R.string.goal_finish), plan?.forecastFinish?.let(::formatShortDate) ?: stringResource(R.string.goal_finish_none), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RiskChip() {
    Row(
        Modifier.clip(CircleShape).background(EnergyColors.WarningSurface).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = EnergyColors.Warning, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.goal_at_risk), style = MaterialTheme.typography.labelMedium, color = EnergyColors.Warning)
    }
}

/** Milestones in order: a check when done, the step number otherwise, with a task bar when it has tasks. */
@Composable
private fun MilestoneList(plan: GoalPlanView, onToggle: (app.assistant.domain.model.MilestoneId, Boolean) -> Unit, modifier: Modifier = Modifier) {
    ApexCard(modifier) {
        plan.milestones.forEachIndexed { i, row ->
            val m = row.milestone
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Checkbox) { onToggle(m.id, !m.done) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (m.done) EnergyColors.Physical.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                        .border(2.dp, if (m.done) EnergyColors.Physical else if (m == plan.nextMilestone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (m.done) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.goal_milestone_done), tint = EnergyColors.Physical, modifier = Modifier.size(16.dp))
                    } else {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, color = if (m.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    val sub = buildList {
                        if (row.tasksTotal > 0) add(stringResource(R.string.goal_tasks_of, row.tasksDone, row.tasksTotal))
                        m.dueDate?.let { add(stringResource(R.string.goal_due_on, formatDate(it))) }
                    }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.tasksTotal > 0 && !m.done) ApexProgressBar(row.tasksDone.toFloat() / row.tasksTotal, MaterialTheme.colorScheme.primary, height = 5.dp)
                }
            }
        }
    }
}

@Composable
private fun GoalCard(
    status: GoalStatus,
    plan: GoalPlanView?,
    onEdit: () -> Unit,
    onLog: () -> Unit,
    onArchive: () -> Unit,
    onPlan: () -> Unit,
    onSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val risk = plan?.atRisk == true
    val color = when {
        risk -> EnergyColors.Warning
        status.isBehind -> EnergyColors.Warning
        else -> EnergyColors.Physical
    }
    ApexCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status.goal.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (risk) RiskChip()
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.task_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.goal_log_progress)) }, onClick = { onLog(); menu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.goal_plan_it)) }, onClick = { onPlan(); menu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.goal_more)) }, onClick = { onSchedule(); menu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { onEdit(); menu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.goal_archive)) }, onClick = { onArchive(); menu = false })
            }
        }
        ApexProgressBar((plan?.percent ?: status.percent) / 100f, color)
        Text(
            stringResource(R.string.goal_progress_line, QuantityParser.format(status.progress), QuantityParser.format(status.goal.target), status.goal.unit, status.percent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when {
                plan?.forecastFinish != null -> stringResource(R.string.goal_forecast, formatDate(plan.forecastFinish))
                status.isBehind -> stringResource(R.string.goal_status_behind, status.daysBehind)
                else -> stringResource(R.string.goal_status_on_track)
            },
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun hours(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.goal_hours_only, minutes / 60) else stringResource(R.string.goal_hours_value, minutes / 60, minutes % 60)

/** The plan proposal: dated checkpoints to review; nothing is stored until "Add to my plan". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanProposalSheet(
    status: GoalStatus,
    propose: (GoalPlanning.StepTitles) -> List<ProposedMilestone>,
    onAccept: (List<ProposedMilestone>) -> Unit,
    onDismiss: () -> Unit,
) {
    val define = stringResource(R.string.plan_step_define)
    val build = stringResource(R.string.plan_step_build)
    val finish = stringResource(R.string.plan_step_finish)
    val reachTemplate = stringResource(R.string.plan_step_reach)
    val steps = remember(status.goal.id) {
        propose(
            object : GoalPlanning.StepTitles {
                override val define = define
                override val build = build
                override val finish = finish
                override fun reach(amount: Long, unit: String) = reachTemplate.format(amount, unit)
            },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow(stringResource(R.string.plan_proposal_eyebrow), color = MaterialTheme.colorScheme.primary)
            Text(status.goal.name, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.plan_proposal_summary, steps.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            steps.forEachIndexed { i, step ->
                TimelineRow(
                    start = "${i + 1}",
                    end = "",
                    title = step.title,
                    subtitle = buildList {
                        add(stringResource(R.string.goal_due_on, formatDate(step.dueDate)))
                        step.estimateMinutes?.let { add(stringResource(R.string.goal_hours_value, it / 60, it % 60)) }
                    }.joinToString(" · "),
                    energy = MaterialTheme.colorScheme.primary,
                    isNow = false,
                    done = false,
                    modifier = Modifier.enterAnimated(i),
                )
            }
            Text(stringResource(R.string.plan_proposal_why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    stringResource(R.string.not_now),
                    onClick = onDismiss,
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurface,
                    border = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                PillButton(stringResource(R.string.plan_accept), onClick = { onAccept(steps) }, modifier = Modifier.weight(2f))
            }
        }
    }
}

/** Sets a goal's deadline and 1–5 priority, which drive the forecast and the scheduler. */
@Composable
private fun GoalPlanningDialog(status: GoalStatus, onSave: (java.time.LocalDate?, Int) -> Unit, onDismiss: () -> Unit) {
    var deadline by rememberSaveable { mutableStateOf(status.goal.deadline) }
    var priority by rememberSaveable { mutableStateOf(status.goal.priority) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_more)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField(label = stringResource(R.string.goal_deadline), value = deadline, onChange = { deadline = it })
                Text(stringResource(R.string.goal_priority_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { p -> FilterChip(selected = p == priority, onClick = { priority = p }, label = { Text("$p") }) }
                }
                Text(stringResource(R.string.goal_priority_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(deadline, priority) }) { Text(stringResource(R.string.subs_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) } },
    )
}

@Composable
private fun LogProgressDialog(onConfirm: (String, (ValidationError?) -> Unit) -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<ValidationError?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_log_title)) },
        text = {
            OutlinedTextField(
                value = amount,
                onValueChange = {
                    amount = it.take(16)
                    error = null
                },
                label = { Text(stringResource(R.string.goal_log_amount)) },
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(errorText(it))) } },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(amount) { err -> if (err == null) onDone() else error = err } }) {
                Text(stringResource(R.string.goal_log_progress))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) } },
    )
}
