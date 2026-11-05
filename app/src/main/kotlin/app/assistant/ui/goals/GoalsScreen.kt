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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

@Composable
fun GoalsScreen(viewModel: GoalsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<GoalEditorTarget?>(null) }
    var logging by rememberSaveable { mutableStateOf<GoalId?>(null) }
    var archiving by rememberSaveable { mutableStateOf<GoalId?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = GoalEditorTarget.New }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.goals_add))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            app.assistant.ui.designsystem.ScreenTitle(stringResource(R.string.nav_goals))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.streak > 0) {
                    item {
                        Text(
                            stringResource(R.string.goals_streak, ui.streak),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (!ui.loading && ui.goals.isEmpty()) {
                    item { app.assistant.ui.designsystem.EmptyState(stringResource(R.string.goals_empty)) }
                }
                items(ui.goals, key = { it.goal.id.value }) { status ->
                    GoalCard(
                        status = status,
                        onEdit = { editing = GoalEditorTarget.Existing(status.goal.id) },
                        onLog = { logging = status.goal.id },
                        onArchive = { archiving = status.goal.id },
                    )
                }
                ui.lastWeek?.let { report ->
                    if (report.goals.isNotEmpty() || report.slipped.isNotEmpty()) {
                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        item {
                            app.assistant.ui.designsystem.SectionHeader(stringResource(R.string.goals_last_week))
                        }
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
}

@Composable
private fun GoalCard(status: GoalStatus, onEdit: () -> Unit, onLog: () -> Unit, onArchive: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(status.goal.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.task_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.goal_log_progress)) }, onClick = {
                        onLog()
                        menu = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                        onEdit()
                        menu = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.goal_archive)) }, onClick = {
                        onArchive()
                        menu = false
                    })
                }
            }
            Text(
                stringResource(
                    R.string.goal_progress_line,
                    QuantityParser.format(status.progress),
                    QuantityParser.format(status.goal.target),
                    status.goal.unit,
                    status.percent,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (status.isBehind) {
                    stringResource(R.string.goal_status_behind, status.daysBehind)
                } else {
                    stringResource(R.string.goal_status_on_track)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (status.isBehind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
