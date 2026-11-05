package app.assistant.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.engine.goals.GoalField
import app.assistant.domain.engine.goals.GoalInput
import app.assistant.domain.engine.goals.GoalValidator
import app.assistant.domain.engine.goals.LinkError
import app.assistant.domain.engine.goals.QuantityParser
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalPeriod
import app.assistant.ui.common.DateField
import app.assistant.ui.common.errorText
import app.assistant.ui.common.periodLabel
import java.time.LocalDate

private const val NAME_MAX = 100
private const val UNIT_MAX = 30
private const val TARGET_MAX = 16

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditorSheet(
    target: GoalEditorTarget,
    allGoals: List<Goal>,
    defaultStart: LocalDate,
    load: suspend (GoalId) -> Goal?,
    onSave: (GoalInput, GoalId?, (FieldErrors<GoalField>?) -> Unit) -> Unit,
    onLink: (GoalId, GoalId?, (LinkError?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val existing = (target as? GoalEditorTarget.Existing)?.id

    var name by remember { mutableStateOf("") }
    var period by remember { mutableStateOf<GoalPeriod?>(null) }
    var start by remember { mutableStateOf<LocalDate?>(defaultStart) }
    var targetText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<GoalId?>(null) }
    var errors by remember { mutableStateOf(FieldErrors.none<GoalField>()) }
    var ready by remember { mutableStateOf(existing == null) }

    LaunchedEffect(existing) {
        if (existing != null) {
            load(existing)?.let { g ->
                name = g.name
                period = g.period
                start = g.startDate
                targetText = QuantityParser.format(g.target)
                unit = g.unit
                parent = g.parentId
            }
            ready = true
        }
    }

    // A parent must be one period larger and active; a goal cannot be its own parent.
    val parentCandidates = remember(allGoals, period, existing) {
        val larger = period?.let(GoalValidator::nextLarger)
        allGoals.filter { it.period == larger && it.isActive && it.id != existing }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (existing == null) R.string.goals_add else R.string.goals_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (!ready) return@Column

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(NAME_MAX)
                    errors = FieldErrors.none()
                },
                label = { Text(stringResource(R.string.goal_name_label)) },
                isError = errors[GoalField.NAME] != null,
                supportingText = errors[GoalField.NAME]?.let { { Text(stringResource(errorText(it))) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PeriodPicker(selected = period, onSelect = {
                period = it
                parent = null
            }, isError = errors[GoalField.PERIOD] != null)
            OutlinedTextField(
                value = targetText,
                onValueChange = {
                    targetText = it.take(TARGET_MAX)
                    errors = FieldErrors.none()
                },
                label = { Text(stringResource(R.string.goal_target_label)) },
                isError = errors[GoalField.TARGET] != null,
                supportingText = errors[GoalField.TARGET]?.let { { Text(stringResource(errorText(it))) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = unit,
                onValueChange = {
                    unit = it.take(UNIT_MAX)
                    errors = FieldErrors.none()
                },
                label = { Text(stringResource(R.string.goal_unit_label)) },
                isError = errors[GoalField.UNIT] != null,
                supportingText = errors[GoalField.UNIT]?.let { { Text(stringResource(errorText(it))) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(label = stringResource(R.string.goal_start_label), value = start, onChange = { start = it }, clearable = false)

            if (parentCandidates.isNotEmpty()) {
                ParentPicker(candidates = parentCandidates, selected = parent, onSelect = { parent = it })
            }

            Button(
                onClick = {
                    val input = GoalInput(name = name, period = period, startDate = start, targetText = targetText, unit = unit)
                    onSave(input, existing) { errs ->
                        if (errs != null) {
                            errors = errs
                        } else if (existing != null) {
                            // Linking is a separate transaction, applied only to an existing goal.
                            onLink(existing, parent) { onDismiss() }
                        } else {
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun PeriodPicker(selected: GoalPeriod?, onSelect: (GoalPeriod) -> Unit, isError: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.let { stringResource(periodLabel(it)) } ?: stringResource(R.string.task_not_set)
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${stringResource(R.string.goal_period_label)}: $label")
        }
        if (isError) {
            Text(stringResource(R.string.error_empty), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GoalPeriod.entries.forEach { p ->
                DropdownMenuItem(text = { Text(stringResource(periodLabel(p))) }, onClick = {
                    onSelect(p)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun ParentPicker(candidates: List<Goal>, selected: GoalId?, onSelect: (GoalId?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = candidates.firstOrNull { it.id == selected }?.name ?: stringResource(R.string.goal_no_parent)
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${stringResource(R.string.goal_parent_label)}: $label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.goal_no_parent)) }, onClick = {
                onSelect(null)
                expanded = false
            })
            candidates.forEach { g ->
                DropdownMenuItem(text = { Text(g.name) }, onClick = {
                    onSelect(g.id)
                    expanded = false
                })
            }
        }
    }
}

sealed interface GoalEditorTarget {
    data object New : GoalEditorTarget

    data class Existing(val id: GoalId) : GoalEditorTarget
}
