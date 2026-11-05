package app.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskStatus
import app.assistant.ui.common.errorText
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.HeroCard
import app.assistant.ui.designsystem.SectionHeader
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private const val MAX_QUICK_ADD = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onOpenReview: (String) -> Unit, viewModel: TodayViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<ValidationError?>(null) }

    // Request AI wording once the rule-based plan for the day is ready (Requirement 48.1). No-op while AI is off.
    androidx.compose.runtime.LaunchedEffect(ui.date) {
        if (ui.date != null) viewModel.requestAiWording()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    TextButton(onClick = { ui.date?.let { onOpenReview(it.toString()) } }) { Text(stringResource(R.string.today_open_review)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                HeroCard {
                    Text(
                        ui.date?.let(::formatDate) ?: stringResource(R.string.today_title),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (ui.streak > 0) {
                        Text(
                            stringResource(R.string.today_streak, ui.streak),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
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
            calendarSection(ui, viewModel)
            item { SectionHeader(stringResource(R.string.today_priorities)) }
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
                item { SectionHeader(stringResource(R.string.today_other_tasks)) }
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
                item { SectionHeader(stringResource(R.string.today_behind_goals)) }
                items(ui.behindGoals, key = { "g${it.goal.id.value}" }) { g ->
                    CredCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Status shown with an icon and text, not colour alone (Requirement 63.6).
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.today_goal_behind, g.goal.name, g.daysBehind), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (ui.dueBills.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.today_bills_due)) }
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
