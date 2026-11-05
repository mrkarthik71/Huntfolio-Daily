package app.assistant.ui.today

import app.assistant.ui.designsystem.ApexIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.FocusHero
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ProactiveCardView
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.SectionHeader
import app.assistant.ui.designsystem.TimelineRow
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val weekdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")
private val dayMonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM")
private val clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private const val MAX_QUICK_ADD = 200

@Composable
fun TodayScreen(onOpenReview: (String) -> Unit, onOpenSettings: () -> Unit = {}, onOpenFocus: () -> Unit = {}, viewModel: TodayViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<ValidationError?>(null) }

    // Request AI wording once the rule-based plan for the day is ready (Requirement 48.1). No-op while AI is off.
    androidx.compose.runtime.LaunchedEffect(ui.date) {
        if (ui.date != null) viewModel.requestAiWording()
    }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.consoleGround()) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics { heading() }) {
                        Caption(ui.date?.format(weekdayFormat).orEmpty())
                        Text(
                            ui.date?.format(dayMonthFormat).orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    KeyButton(ApexIcons.Today, stringResource(R.string.focus_open), onClick = onOpenFocus, tint = MaterialTheme.colorScheme.primary)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    KeyButton(ApexIcons.Review, stringResource(R.string.today_open_review), onClick = {
                        ui.date?.let { onOpenReview(it.toString()) }
                    })
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    KeyButton(ApexIcons.Settings, stringResource(R.string.nav_settings), onClick = onOpenSettings)
                }
            }
            item { TodayHero(ui, Modifier.enterAnimated(0)) }
            ui.day?.let { day ->
                item {
                    Row(Modifier.fillMaxWidth().enterAnimated(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReadoutTile(stringResource(R.string.today_tile_free), durationText(day.freeMinutes), Modifier.weight(1f))
                        ReadoutTile(
                            stringResource(R.string.today_tile_load),
                            stringResource(R.string.percent, day.loadPercent),
                            Modifier.weight(1f),
                            note = if (ui.streak > 0) stringResource(R.string.today_streak_chip, ui.streak) else null,
                            noteColor = EnergyColors.Physical,
                        )
                    }
                }
            }
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
    val facts = emptyList<String>()
    val done = day?.tasksDone ?: 0
    val total = day?.tasksTotal ?: 0
    val doneCaption = stringResource(R.string.today_dial_done)
    if (focus != null) {
        FocusHero(
            eyebrow = stringResource(R.string.today_focus_now, stringResource(energyLabel(focus.energy, focus.kind))),
            title = focus.title,
            subtitle = stringResource(R.string.today_focus_left, minuteText(focus.span.start), minuteText(focus.span.end), day.focusMinutesLeft),
            progress = day.focusProgress,
            facts = facts,
            accent = MaterialTheme.colorScheme.primary,
            modifier = modifier,
            dayDone = done,
            dayTotal = total,
            dayCaption = doneCaption,
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
            dayDone = done,
            dayTotal = total,
            dayCaption = doneCaption,
            accent = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** The day's timeline: meetings, task blocks, routines and free stretches in time order. */
private fun LazyListScope.timelineSection(ui: TodayUi) {
    val day = ui.day ?: return
    if (day.items.isEmpty()) return
    item {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(stringResource(R.string.today_timeline), Modifier.weight(1f))
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
        app.assistant.ui.designsystem.CredInputBar(
            value = text,
            onValueChange = onTextChange,
            hint = stringResource(R.string.today_quick_add_hint),
            actionLabel = stringResource(R.string.today_quick_add_button),
            actionIcon = Icons.Filled.Add,
            onAction = onAdd,
            isError = error != null,
        )
        if (error != null) {
            Text(
                stringResource(errorText(error)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

@Composable
private fun TaskRow(task: Task, subtitle: String?, onDone: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val done = task.status == TaskStatus.DONE
    app.assistant.ui.designsystem.CredSurface {
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
