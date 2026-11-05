package app.assistant.ui.money.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EmptyState
import java.time.ZoneId

/** The Transactions tab: list, add, edit, merge, delete and category change (Requirements 31, 32). */
@Composable
fun TransactionsScreen(modifier: Modifier = Modifier, viewModel: TransactionsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val unknownMerchant = stringResource(R.string.money_txn_no_merchant)

    Column(modifier.fillMaxSize()) {
        Toolbar(ui, viewModel)
        ui.notice?.let { NoticeCard(it, onDismiss = viewModel::dismissNotice) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!ui.loading && ui.items.isEmpty()) {
                item { EmptyState(stringResource(R.string.money_txn_empty)) }
            }
            items(ui.items, key = { it.id.value }) { txn ->
                TransactionRow(
                    txn = txn,
                    zone = ui.zone,
                    merchantText = txn.merchantName ?: unknownMerchant,
                    selecting = ui.selecting,
                    selected = txn.id in ui.selected,
                    onClick = { if (ui.selecting) viewModel.toggleSelected(txn.id) else viewModel.startEdit(txn) },
                    onEdit = { viewModel.startEdit(txn) },
                    onCategory = { viewModel.requestCategory(txn) },
                    onDelete = { viewModel.requestDelete(txn) },
                )
            }
        }
    }

    Dialogs(ui, viewModel)
}

@Composable
private fun Toolbar(ui: TransactionsUi, viewModel: TransactionsViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.selecting) {
            Text(stringResource(R.string.money_txn_merge_hint, ui.selected.size), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::requestMerge, enabled = ui.selected.size == 2, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.money_txn_merge_selected))
                }
                OutlinedButton(onClick = viewModel::cancelMerge, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.money_txn_merge_cancel))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::startAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.money_txn_add)) }
                OutlinedButton(onClick = viewModel::startMerge, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.money_txn_merge_start))
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: TxnNotice, onDismiss: () -> Unit) {
    val text = when (notice.kind) {
        NoticeKind.ADDED -> stringResource(R.string.money_txn_notice_added)
        NoticeKind.SAVED -> stringResource(R.string.money_txn_notice_saved)
        NoticeKind.MERGED -> stringResource(R.string.money_txn_notice_merged)
        NoticeKind.DELETED -> stringResource(R.string.money_txn_notice_deleted)
        NoticeKind.CATEGORY_CHANGED -> stringResource(R.string.money_txn_notice_category_changed)
        NoticeKind.CATEGORY_APPLIED -> stringResource(R.string.money_txn_notice_applied, notice.count)
        NoticeKind.NOT_FOUND -> stringResource(R.string.money_txn_notice_not_found)
        NoticeKind.UNKNOWN_CATEGORY -> stringResource(R.string.money_txn_notice_unknown_category)
        NoticeKind.MERGE_DIFFERENT_TYPE -> stringResource(R.string.money_txn_notice_merge_type)
        NoticeKind.MERGE_SAME -> stringResource(R.string.money_txn_notice_merge_same)
    }
    CredCard(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (notice.isError) Icons.Filled.Warning else Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (notice.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(text, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.money_txn_notice_dismiss)) }
        }
    }
}

@Composable
private fun TransactionRow(
    txn: Transaction,
    zone: ZoneId,
    merchantText: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCategory: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selectLabel = stringResource(R.string.money_txn_select_desc, merchantText)
    val moreLabel = stringResource(R.string.money_txn_more, merchantText)
    CredCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = selectLabel })
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(merchantText, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(typeLabel(txn.type)) + " Â· " + categoryNameOf(txn.category) + " Â· " + formatDate(txn.at.atZone(zone).toLocalDate()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(MoneyFormatter.format(txn.amount), style = MaterialTheme.typography.titleMedium)
            if (!selecting) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = moreLabel) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                        menu = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.money_txn_change_category)) }, onClick = {
                        menu = false
                        onCategory()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
private fun Dialogs(ui: TransactionsUi, viewModel: TransactionsViewModel) {
    when (val dialog = ui.dialog) {
        null -> Unit
        is TransactionsDialog.Editor -> TransactionEditorSheet(
            target = dialog.target,
            errors = dialog.errors,
            today = ui.today,
            zone = ui.zone,
            onSubmit = viewModel::submit,
            onDismiss = viewModel::dismissDialog,
        )
        is TransactionsDialog.Merge -> MergeDialog(
            first = dialog.first,
            second = dialog.second,
            zone = ui.zone,
            onConfirm = viewModel::confirmMerge,
            onDismiss = viewModel::dismissDialog,
        )
        is TransactionsDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.money_txn_delete_title)) },
            text = { Text(stringResource(R.string.money_txn_delete_body)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.cancel)) } },
        )
        is TransactionsDialog.PickCategory -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.money_txn_category_title)) },
            text = {
                Column {
                    BuiltinCategories.NAMES.forEachIndexed { index, name ->
                        TextButton(
                            onClick = { viewModel.chooseCategory(dialog.target, CategoryId(index + 1L)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.cancel)) } },
        )
        is TransactionsDialog.Offer -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.money_txn_offer_title)) },
            text = { Text(stringResource(R.string.money_txn_offer_body, dialog.offer.count, dialog.categoryName)) },
            confirmButton = { TextButton(onClick = viewModel::applyOffer) { Text(stringResource(R.string.money_txn_offer_apply)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.money_txn_offer_skip)) } },
        )
    }
}
