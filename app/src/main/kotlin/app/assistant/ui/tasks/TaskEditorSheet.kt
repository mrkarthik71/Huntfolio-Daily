package app.assistant.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.engine.tasks.TaskField
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.ui.common.DateField
import app.assistant.ui.common.errorText
import java.time.LocalDate

private const val TITLE_MAX = 200
private const val ESTIMATE_MAX_LEN = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    target: EditorTarget,
    goals: List<Goal>,
    defaultPlanned: LocalDate,
    load: suspend (TaskId) -> Task?,
    onSave: (TaskInput, TaskId?, (FieldErrors<TaskField>?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val existing = (target as? EditorTarget.Existing)?.id

    var title by remember { mutableStateOf((target as? EditorTarget.Prefilled)?.title.orEmpty()) }
    var estimate by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var planned by remember { mutableStateOf<LocalDate?>(defaultPlanned) }
    var goalId by remember { mutableStateOf<GoalId?>(null) }
    var errors by remember { mutableStateOf(FieldErrors.none<TaskField>()) }
    var ready by remember { mutableStateOf(existing == null) }

    LaunchedEffect(existing) {
        if (existing != null) {
            load(existing)?.let { t ->
                title = t.title
                estimate = t.estimateMinutes?.toString() ?: ""
                due = t.dueDate
                planned = t.plannedDate
                goalId = t.goalId
            }
            ready = true
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (existing == null) R.string.tasks_add else R.string.tasks_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (!ready) return@Column

            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it.take(TITLE_MAX)
                    errors = FieldErrors.none()
                },
                label = { Text(stringResource(R.string.task_title_label)) },
                isError = errors[TaskField.TITLE] != null,
                supportingText = errors[TaskField.TITLE]?.let { { Text(stringResource(errorText(it))) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = estimate,
                onValueChange = { v ->
                    estimate = v.filter(Char::isDigit).take(ESTIMATE_MAX_LEN)
                    errors = FieldErrors.none()
                },
                label = { Text(stringResource(R.string.task_estimate_label)) },
                isError = errors[TaskField.ESTIMATE] != null,
                supportingText = errors[TaskField.ESTIMATE]?.let { { Text(stringResource(errorText(it))) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(label = stringResource(R.string.task_planned_label), value = planned, onChange = { planned = it })
            DateField(label = stringResource(R.string.task_due_label), value = due, onChange = { due = it })
            GoalPicker(goals = goals, selected = goalId, onSelect = { goalId = it })

            Button(
                onClick = {
                    onSave(
                        TaskInput(
                            title = title,
                            dueDate = due,
                            plannedDate = planned,
                            estimateText = estimate,
                            goalId = goalId,
                        ),
                        existing,
                    ) { errs -> if (errs == null) onDismiss() else errors = errs }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun GoalPicker(goals: List<Goal>, selected: GoalId?, onSelect: (GoalId?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = goals.firstOrNull { it.id == selected }?.name ?: stringResource(R.string.task_no_goal)
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        androidx.compose.material3.OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${stringResource(R.string.task_goal_label)}: $selectedName")
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.task_no_goal)) }, onClick = {
                onSelect(null)
                expanded = false
            })
            goals.forEach { g ->
                DropdownMenuItem(text = { Text(g.name) }, onClick = {
                    onSelect(g.id)
                    expanded = false
                })
            }
        }
    }
}
