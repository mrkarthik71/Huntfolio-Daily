package app.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.schedule.DayItemKind
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskStatus
import app.assistant.ui.common.errorText
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.FocusHero
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.ProactiveCardView
import app.assistant.ui.designsystem.SectionHeader
import app.assistant.ui.designsystem.TimelineRow
import app.assistant.ui.designsystem.enterAnimated
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private const val MAX_QUICK_ADD = 200

@Composable
fun TodayScreen(onOpenReview: (String) -> Unit, viewModel: TodayViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<ValidationError?>(null) }

    // Request AI wording once the rule-based plan for the day is ready (Requirement 48.1). No-op while AI is off.
    androidx.compose.runtime.LaunchedEffect(ui.date) {
        if (ui.date != null) viewModel.requestAiWording()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ui.date?.let(::formatDate).orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.today_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    }
                    PillButton(
                        stringResource(R.string.today_open_review),
                        onClick = { ui.date?.let { onOpenReview(it.toString()) } },
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurface,
                        border = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            item { TodayHero(ui, Modifier.enterAnimated(0)) }
            itemsIndexed(ui.cards, key = { _, c -> "card${c.id.value}" }) { i, card ->
                ProactiveCardView(
                    title = card.title,
                    body = card.reason,
                    acceptLabel = if (card.kind ==
                        app.assistant.domain.model.CardKind.FREE_SLOT
                    ) {
                        stringResource(R.string.card_fill_slot)
                    } else {
                        stringResource(R.string.card_got_it)
                    },
                    dismissLabel = stringResource(R.string.card_dismiss),
                    whyLabel = stringResource(R.string.card_why),
                    why = stringResource(cardWhy(card.kind)),
                    onAccept = { viewModel.acceptCard(card.id) },
                    onDismiss = { viewModel.dismissCard(card.id) },
                    modifier = Modifier.enterAnimated(i + 1),
                )
            }
            item {
                QuickAdd(
                    text = text,
                    error = error,
                    onTextChange = {
                        text = it.take(MAX_QUICK_ADD)
                        error = null
                    },
                    onAdd = {
                        viewModel.quickAdd(text) { result ->
                            when (result) {
                                is Outcome.Ok -> {
                                    text = ""
                                    error = null
                                }
                                is Outcome.Err -> error = result.error
                            }
                        }
                    },
                )
            }
            aiWordingSection(ui)
            timelineSection(ui)
            calendarSection(ui, viewModel)
            item { SectionHeader(stringResource(R.string.today_priorities).uppercase()) }
            if (!ui.loading && ui.priorities.isEmpty()) {
                item { EmptyLine(stringResource(R.string.today_no_priorities)) }
            }
            items(ui.priorities, key = { "p${it.task.id.value}" }) { row ->
                val block = row.block?.let { b ->
                    stringResource(R.string.today_time_block, b.range.start.atZone(ui.zone).format(clock), b.range.end.atZone(ui.zone).format(clock))
                }
                TaskRow(
                    task = row.task,
                    subtitle = block,
                    onDone = { viewModel.markDone(row.task.id) },
                    menu = { close ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.today_move_up)) }, onClick = {
                            viewModel.move(row.task.id, -1)
                            close()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.today_move_down)) }, onClick = {
                            viewModel.move(row.task.id, 1)
                            close()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.today_remove_priority)) }, onClick = {
                            viewModel.removePriority(row.task.id)
                            close()
                        })
                    },
                )
            }
            if (ui.otherTasks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_other_tasks).uppercase()) }
                items(ui.otherTasks, key = { "o${it.id.value}" }) { task ->
                    TaskRow(
                        task = task,
                        subtitle = null,
                        onDone = { viewModel.markDone(task.id) },
                        menu = { close ->
                            if (ui.priorities.size < 3) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.today_make_priority)) }, onClick = {
                                    viewModel.makePriority(task.id)
                                    close()
                                })
                            }
                        },
                    )
                }
            }
            if (ui.behindGoals.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_behind_goals).uppercase()) }
                items(ui.behindGoals, key = { "g${it.goal.id.value}" }) { g ->
                    CredCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Status shown with an icon and text, not colour alone (Requirement 63.6).
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = EnergyColors.Warning)
                            Text(stringResource(R.string.today_goal_behind, g.goal.name, g.daysBehind), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (ui.dueBills.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_bills_due).uppercase()) }
                items(ui.dueBills, key = { "b${it.id.value}" }) { bill ->
                    CredCard {
                        Text(
                            stringResource(R.string.today_bill_due, bill.name, MoneyFormatter.format(bill.amount), formatDate(bill.dueDate)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

/** The focus-now hero: the block covering now, else what is next, with load, streak and done counts. */
@Composable
private fun TodayHero(ui: TodayUi, modifier: Modifier = Modifier) {
    val day = ui.day
    val focus = day?.focusNow
    val facts = buildList {
        if (day != null) add(stringResource(R.string.today_load_chip, day.loadPercent))
        if (ui.streak > 0) add(stringResource(R.string.today_streak_chip, ui.streak))
        if (day != null && day.tasksTotal > 0) add(stringResource(R.string.today_done_chip, day.tasksDone, day.tasksTotal))
    }
    if (focus != null) {
        FocusHero(
            eyebrow = stringResource(R.string.today_focus_now, stringResource(energyLabel(focus.energy, focus.kind))),
            title = focus.title,
            subtitle = stringResource(R.string.today_focus_left, minuteText(focus.span.start), minuteText(focus.span.end), day.focusMinutesLeft),
            progress = day.focusProgress,
            facts = facts,
            accent = EnergyColors.of(focus.energy).takeIf { focus.kind != DayItemKind.MEETING } ?: MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    } else {
        val next = day?.items?.firstOrNull { it.kind != DayItemKind.FREE && !it.done && it.span.start >= (day.items.firstOrNull()?.span?.start ?: 0) }
        FocusHero(
            eyebrow = stringResource(R.string.today_free_now),
            title = ui.date?.let(::formatDate) ?: stringResource(R.string.today_title),
            subtitle = next?.let { stringResource(R.string.today_next_up, it.title, minuteText(it.span.start)) },
            progress = null,
            facts = facts,
            modifier = modifier,
        )
    }
}

/** The day's timeline: meetings, task blocks, routines and free stretches in time order. */
private fun LazyListScope.timelineSection(ui: TodayUi) {
    val day = ui.day ?: return
    if (day.items.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.today_timeline).uppercase(), Modifier.weight(1f))
            Text(
                stringResource(R.string.today_booked, durationText(day.bookedMinutes), durationText(day.freeMinutes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    itemsIndexed(day.items, key = { _, row -> row.key }) { i, item ->
        val free = item.kind == DayItemKind.FREE
        TimelineRow(
            start = minuteText(item.span.start),
            end = minuteText(item.span.end),
            title = if (free) stringResource(R.string.today_free_slot, item.span.minutes) else item.title,
            subtitle = if (free) stringResource(R.string.today_free_slot_hint) else stringResource(kindLabel(item)),
            energy = if (free) null else EnergyColors.of(item.energy),
            isNow = item.key == day.focusNow?.key,
            done = item.done,
            dashed = free,
            modifier = Modifier.enterAnimated(i),
        )
    }
}

private fun minuteText(m: Int): String = "%02d:%02d".format(m / MINUTES_PER_HOUR % HOURS_PER_DAY, m % MINUTES_PER_HOUR)

@Composable
private fun durationText(minutes: Int): String = if (minutes >=
    MINUTES_PER_HOUR
) {
    stringResource(R.string.duration_h_m, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
} else {
    stringResource(R.string.duration_m, minutes)
}

private fun energyLabel(tag: app.assistant.domain.model.EnergyTag?, kind: DayItemKind): Int = when {
    kind == DayItemKind.MEETING -> R.string.kind_meeting
    tag == app.assistant.domain.model.EnergyTag.DEEP_WORK -> R.string.energy_deep_work
    tag == app.assistant.domain.model.EnergyTag.PHYSICAL_ROUTINE -> R.string.energy_physical
    tag == app.assistant.domain.model.EnergyTag.REST -> R.string.energy_rest
    else -> R.string.energy_light_admin
}

private fun kindLabel(item: app.assistant.domain.engine.schedule.DayItem): Int = when (item.kind) {
    DayItemKind.MEETING -> R.string.kind_meeting
    DayItemKind.ROUTINE -> R.string.kind_routine
    else -> energyLabel(item.energy, item.kind)
}

private fun cardWhy(kind: app.assistant.domain.model.CardKind): Int = when (kind) {
    app.assistant.domain.model.CardKind.OVERLOAD -> R.string.card_why_overload
    app.assistant.domain.model.CardKind.GOAL_AT_RISK -> R.string.card_why_goal
    app.assistant.domain.model.CardKind.RENEWAL_SOON -> R.string.card_why_renewal
    app.assistant.domain.model.CardKind.FREE_SLOT -> R.string.card_why_free
    app.assistant.domain.model.CardKind.ROUTINE_MISSED -> R.string.card_why_routine
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
    )
}

@Composable
private fun QuickAdd(text: String, error: ValidationError?, onTextChange: (String) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text(stringResource(R.string.today_quick_add_hint)) },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.today_quick_add_button))
            }
        }
        if (error != null) Text(stringResource(errorText(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TaskRow(task: Task, subtitle: String?, onDone: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val done = task.status == TaskStatus.DONE
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = done,
                onCheckedChange = { if (!done) onDone() },
                enabled = !done,
                modifier = Modifier.semantics { contentDescription = task.title },
            )
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                if (done) {
                    Text(
                        stringResource(R.string.today_task_done),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!done) {
                IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.task_more)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
            }
        }
    }
}
