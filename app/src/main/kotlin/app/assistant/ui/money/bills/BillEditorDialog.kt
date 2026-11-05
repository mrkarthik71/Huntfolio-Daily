package app.assistant.ui.money.bills

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillValidator
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.model.BillRecurrence
import app.assistant.ui.common.DateField
import app.assistant.ui.money.budgets.MoneyFieldError

private const val AMOUNT_MAX = 16

@StringRes
internal fun billRecurrenceLabel(recurrence: BillRecurrence): Int = when (recurrence) {
    BillRecurrence.NONE -> R.string.money_bill_recurrence_none
    BillRecurrence.MONTHLY -> R.string.money_bill_recurrence_monthly
    BillRecurrence.YEARLY -> R.string.money_bill_recurrence_yearly
}

/**
 * The form for a new Bill, an edited one, or a proposal the User is reviewing (Requirements 34.1, 34.2, 34.6). A rejected form stays open
 * and shows what is wrong beside each field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillEditorDialog(title: String, initial: BillForm, onSave: (BillForm, (FieldErrors<BillField>?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var amountText by remember { mutableStateOf(initial.amountText) }
    var dueDate by remember { mutableStateOf(initial.dueDate) }
    var recurrence by remember { mutableStateOf(initial.recurrence) }
    var errors by remember { mutableStateOf(FieldErrors.none<BillField>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(BillValidator.NAME_MAX)
                        errors = FieldErrors.none()
                    },
                    label = { Text(stringResource(R.string.money_bill_name_label)) },
                    isError = errors[BillField.NAME] != null,
                    supportingText = errors[BillField.NAME]?.let { { MoneyFieldError(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.take(AMOUNT_MAX)
                        errors = FieldErrors.none()
                    },
                    label = { Text(stringResource(R.string.money_bill_amount_label)) },
                    isError = errors[BillField.AMOUNT] != null,
                    supportingText = errors[BillField.AMOUNT]?.let { { MoneyFieldError(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(
                    label = stringResource(R.string.money_bill_due_label),
                    value = dueDate,
                    onChange = {
                        dueDate = it
                        errors = FieldErrors.none()
                    },
                    clearable = false,
                )
                errors[BillField.DUE_DATE]?.let { MoneyFieldError(it) }
                Text(stringResource(R.string.money_bill_repeat_label))
                BillRecurrence.entries.forEach { option ->
                    FilterChip(
                        selected = recurrence == option,
                        onClick = { recurrence = option },
                        label = { Text(stringResource(billRecurrenceLabel(option))) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(BillForm(name, amountText, dueDate, recurrence, initial.merchant)) { result ->
                    if (result == null) onDismiss() else errors = result
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
