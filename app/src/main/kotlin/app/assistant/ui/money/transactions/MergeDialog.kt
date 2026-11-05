package app.assistant.ui.money.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.MergeSide
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.format.MoneyFormatter
import app.assistant.ui.common.formatDate
import app.assistant.ui.common.formatTime
import java.time.ZoneId

/** Chooses, field by field, which value the merged Transaction keeps (Requirement 32.4). Fields both agree on need no choice. */
@Composable
fun MergeDialog(first: Transaction, second: Transaction, zone: ZoneId, onConfirm: (MergeSelection) -> Unit, onDismiss: () -> Unit) {
    var amountSide by remember { mutableStateOf(MergeSide.FIRST) }
    var dateSide by remember { mutableStateOf(MergeSide.FIRST) }
    var merchantSide by remember { mutableStateOf(MergeSide.FIRST) }

    val amountDiffers = first.amount != second.amount
    val dateDiffers = first.at != second.at
    val merchantDiffers = !first.merchantName.orEmpty().equals(second.merchantName.orEmpty(), ignoreCase = true)
    val unknownMerchant = stringResource(R.string.money_txn_no_merchant)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.money_txn_merge_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (amountDiffers || dateDiffers || merchantDiffers) R.string.money_txn_merge_body else R.string.money_txn_merge_same),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (amountDiffers) {
                    SideChoice(
                        label = stringResource(R.string.money_txn_amount_label),
                        firstText = MoneyFormatter.format(first.amount),
                        secondText = MoneyFormatter.format(second.amount),
                        selected = amountSide,
                        onSelect = { amountSide = it },
                    )
                }
                if (dateDiffers) {
                    SideChoice(
                        label = stringResource(R.string.money_txn_date_label),
                        firstText = dateTimeText(first, zone),
                        secondText = dateTimeText(second, zone),
                        selected = dateSide,
                        onSelect = { dateSide = it },
                    )
                }
                if (merchantDiffers) {
                    SideChoice(
                        label = stringResource(R.string.money_txn_merchant_label),
                        firstText = first.merchantName ?: unknownMerchant,
                        secondText = second.merchantName ?: unknownMerchant,
                        selected = merchantSide,
                        onSelect = { merchantSide = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(MergeSelection(amount = amountSide, date = dateSide, merchant = merchantSide)) }) {
                Text(stringResource(R.string.money_txn_merge_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun dateTimeText(txn: Transaction, zone: ZoneId): String {
    val zoned = txn.at.atZone(zone)
    return formatDate(zoned.toLocalDate()) + " " + formatTime(zoned.toLocalTime())
}

@Composable
private fun SideChoice(label: String, firstText: String, secondText: String, selected: MergeSide, onSelect: (MergeSide) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        listOf(MergeSide.FIRST to firstText, MergeSide.SECOND to secondText).forEach { (side, text) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = side == selected, role = Role.RadioButton, onClick = { onSelect(side) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = side == selected, onClick = null)
                Text(text, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
