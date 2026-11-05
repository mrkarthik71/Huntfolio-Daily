package app.assistant.ui.money.transactions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.TxnType
import app.assistant.ui.common.DateField
import app.assistant.ui.common.errorText
import java.time.LocalDate
import java.time.ZoneId

private const val AMOUNT_MAX = 16
private const val MERCHANT_MAX = 100

/** Adds a Transaction by hand ([target] null, Requirement 32.3) or edits one (Requirement 32.1). Errors come from the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorSheet(
    target: Transaction?,
    errors: FieldErrors<EditedField>,
    today: LocalDate,
    zone: ZoneId,
    onSubmit: (TransactionForm) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var type by remember { mutableStateOf(target?.type ?: TxnType.DEBIT) }
    var amountText by remember { mutableStateOf(target?.let { MoneyText.plain(it.amount) }.orEmpty()) }
    var date by remember { mutableStateOf<LocalDate?>(target?.at?.atZone(zone)?.toLocalDate() ?: today) }
    var merchant by remember { mutableStateOf(target?.merchantName.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (target == null) R.string.money_txn_add_title else R.string.money_txn_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            TypePicker(selected = type, onSelect = { type = it })
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.take(AMOUNT_MAX) },
                label = { Text(stringResource(R.string.money_txn_amount_label)) },
                isError = errors[EditedField.AMOUNT] != null,
                supportingText = errors[EditedField.AMOUNT]?.let { { FieldError(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(
                label = stringResource(R.string.money_txn_date_label),
                value = date,
                onChange = { date = it },
                clearable = false,
                error = errors[EditedField.DATE]?.let { stringResource(errorText(it)) },
            )
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it.take(MERCHANT_MAX) },
                label = { Text(stringResource(R.string.money_txn_merchant_label)) },
                isError = errors[EditedField.MERCHANT] != null,
                supportingText = errors[EditedField.MERCHANT]?.let { { FieldError(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSubmit(TransactionForm(type, amountText, date, merchant)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

/** An error as a warning icon and text, so it never depends on colour alone. */
@Composable
private fun FieldError(error: ValidationError) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(stringResource(errorText(error)))
    }
}

@Composable
private fun TypePicker(selected: TxnType, onSelect: (TxnType) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(stringResource(R.string.money_txn_type_label), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TxnType.entries.forEach { option ->
                val label = stringResource(typeLabel(option))
                Row(
                    Modifier.heightIn(min = 48.dp).selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@StringRes
fun typeLabel(type: TxnType): Int = when (type) {
    TxnType.DEBIT -> R.string.money_txn_type_debit
    TxnType.CREDIT -> R.string.money_txn_type_credit
}
