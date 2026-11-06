package app.assistant.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.ui.common.formatDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(prefillTitle: String? = null, viewModel: TasksViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<EditorTarget?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<TaskId?>(null) }
    val scope = rememberCoroutineScope()

    // A non-blank prefill from a mail "Create task" opens the editor pre-filled (Requirement 57.1).
    androidx.compose.runtime.LaunchedEffect(prefillTitle) {
        if (!prefillTitle.isNullOrBlank()) editing = EditorTarget.Prefilled(prefillTitle)
    }

    Scaffold(
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { editing = EditorTarget.New },
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                shape = androidx.compose.foundation.shape.CircleShape,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.tasks_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            app.assistant.ui.designsystem.ScreenTitle(stringResource(R.string.nav_tasks))
            if (!ui.loading && ui.tasks.isEmpty()) {
                app.assistant.ui.designsystem.EmptyState(stringResource(R.string.tasks_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.tasks, key = { it.id.value }) { task ->
                        TaskListItem(
                            task = task,
                            onDone = { viewModel.markDone(task.id) },
                            onEdit = { editing = EditorTarget.Existing(task.id) },
                            onMoveTomorrow = { viewModel.moveToTomorrow(task.id) },
                            onDrop = { viewModel.drop(task.id) },
                            onDelete = { confirmDelete = task.id },
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        TaskEditorSheet(
            target = target,
            goals = ui.goals,
            defaultPlanned = viewModel.today(),
            load = { viewModel.load(it) },
            onSave = { input, existing, onErrors -> viewModel.save(input, existing, onErrors) },
            onDismiss = { editing = null },
        )
    }

    confirmDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.task_delete_title)) },
            text = { Text(stringResource(R.string.task_delete_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch { viewModel.delete(id) }
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }
}

@Composable
private fun TaskListItem(task: Task, onDone: () -> Unit, onEdit: () -> Unit, onMoveTomorrow: () -> Unit, onDrop: () -> Unit, onDelete: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val done = task.status == TaskStatus.DONE
    val subtitle = buildList {
        task.plannedDate?.let { add(stringResource(R.string.task_planned, formatDate(it))) }
        task.dueDate?.let { add(stringResource(R.string.task_due, formatDate(it))) }
        task.estimateMinutes?.let { add(stringResource(R.string.task_estimate, it)) }
    }.joinToString("  ·  ")

    ListItem(
        leadingContent = {
            Checkbox(
                checked = done,
                onCheckedChange = { if (!done) onDone() },
                enabled = !done,
                modifier = Modifier.semantics { contentDescription = task.title },
            )
        },
        headlineContent = {
            Text(task.title, textDecoration = if (done) TextDecoration.LineThrough else null)
        },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        } else {
            null
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.task_more))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (!done) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                            onEdit()
                            open = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.task_move_tomorrow)) }, onClick = {
                            onMoveTomorrow()
                            open = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.task_drop)) }, onClick = {
                            onDrop()
                            open = false
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                        onDelete()
                        open = false
                    })
                }
            }
        },
    )
}

sealed interface EditorTarget {
    data object New : EditorTarget

    data class Existing(val id: TaskId) : EditorTarget

    /** Opened pre-filled from a mail item (Requirement 57.1); the user still confirms or cancels. */
    data class Prefilled(val title: String) : EditorTarget
}
