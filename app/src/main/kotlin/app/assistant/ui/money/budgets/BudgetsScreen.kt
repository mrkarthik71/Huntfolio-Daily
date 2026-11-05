package app.assistant.ui.money.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.budget.BudgetField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.CategoryId
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EmptyState
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val AMOUNT_MAX = 16
private const val FULL_PERCENT = 100L
private const val PERCENT_AS_FRACTION = 100f

/** The month as words, in the device language, for example "November 2026". */
fun moneyMonthLabel(month: YearMonth): String = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))

/** The Budgets tab of the Money screen (Requirement 33). */
@Composable
fun BudgetsScreen(modifier: Modifier = Modifier, viewModel: BudgetsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorCategory by rememberSaveable { mutableStateOf<Long?>(null) }
    var removing by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = viewModel::previousMonth) {
                Text(stringResource(R.string.money_budget_prev_month))
            }
            Text(moneyMonthLabel(ui.month), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::nextMonth, enabled = ui.canGoNext) { Text(stringResource(R.string.money_budget_next_month)) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(
                    onClick = {
                        editorCategory = null
                        editorOpen = true
                    },
                    enabled = ui.available.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.money_budget_add)) }
            }
            if (!ui.loading && ui.rows.isEmpty()) {
                item { EmptyState(stringResource(R.string.money_budget_empty)) }
            }
            items(ui.rows, key = { it.category.id.value }) { row ->
                BudgetCard(
                    row = row,
                    onEdit = {
                        editorCategory = row.category.id.value
                        editorOpen = true
                    },
                    onRemove = { removing = row.category.id.value },
                )
            }
        }
    }

    if (editorOpen) {
        val target = ui.rows.firstOrNull { it.category.id.value == editorCategory }
        BudgetEditorDialog(
            fixed = target?.category,
            initialAmount = target?.let { MoneyAmountText.plain(it.amount) }.orEmpty(),
            available = ui.available,
            onSave = { category, text, onResult -> viewModel.save(category, text, onResult) },
            onDismiss = { editorOpen = false },
        )
    }

    removing?.let { id ->
        val name = BudgetCategories.of(CategoryId(id)).name
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.money_budget_remove_title)) },
            text = { Text(stringResource(R.string.money_budget_remove_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(CategoryId(id))
                    removing = null
                }) { Text(stringResource(R.string.money_budget_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BudgetCard(row: BudgetRow, onEdit: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val status = row.status
    val overBy = status.overBy
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.money_budget_more, row.category.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.money_budget_edit)) }, onClick = {
                        menu = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.money_budget_remove)) }, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        }
        Text(
            stringResource(R.string.money_budget_spent_of, MoneyFormatter.format(status.spent), MoneyFormatter.format(row.amount)),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { status.percentUsed.coerceIn(0L, FULL_PERCENT).toFloat() / PERCENT_AS_FRACTION },
            color = if (overBy != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Text(
            stringResource(R.string.money_budget_percent, status.percentUsed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (overBy != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.money_budget_over, MoneyFormatter.format(overBy)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(stringResource(R.string.money_budget_left, MoneyFormatter.format(status.remaining)), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun BudgetEditorDialog(
    fixed: BudgetCategory?,
    initialAmount: String,
    available: List<BudgetCategory>,
    onSave: (CategoryId?, String, (FieldErrors<BudgetField>?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(fixed) }
    var amountText by remember { mutableStateOf(initialAmount) }
    var errors by remember { mutableStateOf(FieldErrors.none<BudgetField>()) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (fixed == null) stringResource(R.string.money_budget_editor_add) else stringResource(R.string.money_budget_editor_edit, fixed.name))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fixed == null) {
                    if (available.isEmpty()) {
                        Text(stringResource(R.string.money_budget_no_categories))
                    } else {
                        Box {
                            val categoryLabel = selected?.name ?: stringResource(R.string.money_budget_pick_category)
                            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("${stringResource(R.string.money_budget_category)}: $categoryLabel")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                available.forEach { category ->
                                    DropdownMenuItem(text = { Text(category.name) }, onClick = {
                                        selected = category
                                        errors = FieldErrors.none()
                                        menu = false
                                    })
                                }
                            }
                        }
                    }
                    errors[BudgetField.CATEGORY]?.let { MoneyFieldError(it) }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.take(AMOUNT_MAX)
                        errors = FieldErrors.none()
                    },
                    label = { Text(stringResource(R.string.money_budget_amount)) },
                    isError = errors[BudgetField.AMOUNT] != null,
                    supportingText = errors[BudgetField.AMOUNT]?.let { { MoneyFieldError(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(selected?.id, amountText) { result ->
                    if (result == null) onDismiss() else errors = result
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
