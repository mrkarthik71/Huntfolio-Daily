package app.assistant.ui.money.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.port.BillProposal
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EmptyState
import app.assistant.ui.designsystem.SectionHeader
import app.assistant.ui.money.budgets.MoneyAmountText
import java.time.LocalDate

private sealed interface BillEditorTarget {
    data object New : BillEditorTarget

    data class Existing(val bill: BillOccurrence) : BillEditorTarget

    data class Proposal(val proposal: BillProposal) : BillEditorTarget
}

/** The Bills tab of the Money screen (Requirement 34). */
@Composable
fun BillsScreen(modifier: Modifier = Modifier, viewModel: BillsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<BillEditorTarget?>(null) }
    var deleting by remember { mutableStateOf<BillOccurrence?>(null) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "add") {
            Button(onClick = { editor = BillEditorTarget.New }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.money_bill_add)) }
        }
        ui.notice?.let { notice ->
            item(key = "notice") { NoticeCard(notice, onDismiss = viewModel::dismissNotice) }
        }
        val nothing = ui.unpaid.isEmpty() && ui.paid.isEmpty() && ui.proposals.isEmpty() && ui.paidProposals.isEmpty()
        if (!ui.loading && nothing) {
            item(key = "empty") { EmptyState(stringResource(R.string.money_bill_empty)) }
        }
        if (ui.proposals.isNotEmpty()) {
            item(key = "h-proposals") { SectionHeader(stringResource(R.string.money_bill_section_proposals)) }
            items(ui.proposals, key = { "proposal-${it.id}" }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onReview = { editor = BillEditorTarget.Proposal(proposal) },
                    onDismiss = { viewModel.dismissProposal(proposal.id) },
                )
            }
        }
        if (ui.paidProposals.isNotEmpty()) {
            item(key = "h-paid-proposals") { SectionHeader(stringResource(R.string.money_bill_section_paid_proposals)) }
            items(ui.paidProposals, key = { "paid-proposal-${it.transaction.value}-${it.bill.id.value}" }) { row ->
                PaidProposalCard(row = row, onYes = { viewModel.acceptPaidProposal(row) }, onNo = { viewModel.dismissPaidProposal(row) })
            }
        }
        if (ui.unpaid.isNotEmpty()) {
            item(key = "h-unpaid") { SectionHeader(stringResource(R.string.money_bill_section_unpaid)) }
            items(ui.unpaid, key = { "unpaid-${it.id.value}" }) { bill ->
                BillCard(
                    bill = bill,
                    today = ui.today,
                    onMarkPaid = { viewModel.markPaid(bill) },
                    onEdit = { editor = BillEditorTarget.Existing(bill) },
                    onDelete = { deleting = bill },
                )
            }
        }
        if (ui.paid.isNotEmpty()) {
            item(key = "h-paid") { SectionHeader(stringResource(R.string.money_bill_section_paid)) }
            items(ui.paid, key = { "paid-${it.id.value}" }) { bill ->
                BillCard(
                    bill = bill,
                    today = ui.today,
                    onMarkPaid = { viewModel.markPaid(bill) },
                    onEdit = { editor = BillEditorTarget.Existing(bill) },
                    onDelete = { deleting = bill },
                )
            }
        }
    }

    editor?.let { target ->
        val title = stringResource(
            when (target) {
                BillEditorTarget.New -> R.string.money_bill_editor_add
                is BillEditorTarget.Existing -> R.string.money_bill_editor_edit
                is BillEditorTarget.Proposal -> R.string.money_bill_editor_review
            },
        )
        BillEditorDialog(
            title = title,
            initial = initialForm(target, ui.today),
            onSave = { form, onResult ->
                when (target) {
                    BillEditorTarget.New -> viewModel.save(null, form, onResult)
                    is BillEditorTarget.Existing -> viewModel.save(target.bill.id, form, onResult)
                    is BillEditorTarget.Proposal -> viewModel.acceptProposal(target.proposal.id, form, onResult)
                }
            },
            onDismiss = { editor = null },
        )
    }

    deleting?.let { bill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.money_bill_delete_title)) },
            text = { Text(stringResource(R.string.money_bill_delete_body, bill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(bill.id)
                    deleting = null
                }) { Text(stringResource(R.string.money_bill_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun initialForm(target: BillEditorTarget, today: LocalDate): BillForm = when (target) {
    BillEditorTarget.New -> BillForm(name = "", amountText = "", dueDate = today, recurrence = BillRecurrence.NONE)
    is BillEditorTarget.Existing -> BillForm(
        name = target.bill.name,
        amountText = MoneyAmountText.plain(target.bill.amount),
        dueDate = target.bill.dueDate,
        recurrence = target.bill.recurrence,
    )
    is BillEditorTarget.Proposal -> BillForm(
        name = target.proposal.merchantName.orEmpty(),
        amountText = MoneyAmountText.plain(target.proposal.amount),
        dueDate = target.proposal.dueDate,
        recurrence = BillRecurrence.NONE,
        merchant = target.proposal.merchantName,
    )
}

@Composable
private fun NoticeCard(notice: BillNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        is BillNotice.PaidWithNext -> stringResource(R.string.money_bill_notice_paid_next, notice.name, formatDate(notice.next))
        is BillNotice.Paid -> stringResource(R.string.money_bill_notice_paid, notice.name)
        BillNotice.Failed -> stringResource(R.string.money_bill_notice_failed)
    }
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (notice is BillNotice.Failed) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.money_bill_notice_dismiss)) }
    }
}

@Composable
private fun ProposalCard(proposal: BillProposal, onReview: () -> Unit, onDismiss: () -> Unit) {
    val due = proposal.dueDate?.let { stringResource(R.string.money_bill_due_on, formatDate(it)) } ?: stringResource(R.string.money_bill_no_due_date)
    CredCard {
        Text(
            proposal.merchantName ?: stringResource(R.string.money_bill_proposal_name_missing),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.money_bill_proposal_line, MoneyFormatter.format(proposal.amount), due),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onReview) { Text(stringResource(R.string.money_bill_proposal_review)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.money_bill_proposal_dismiss)) }
        }
    }
}

@Composable
private fun PaidProposalCard(row: PaidProposalRow, onYes: () -> Unit, onNo: () -> Unit) {
    val bill = row.bill
    CredCard {
        Text(
            stringResource(
                R.string.money_bill_paid_proposal_line,
                bill.name,
                MoneyFormatter.format(bill.amount),
                stringResource(R.string.money_bill_due_on, formatDate(bill.dueDate)),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onYes) { Text(stringResource(R.string.money_bill_paid_proposal_yes)) }
            TextButton(onClick = onNo) { Text(stringResource(R.string.money_bill_paid_proposal_no)) }
        }
    }
}

@Composable
private fun BillCard(bill: BillOccurrence, today: LocalDate, onMarkPaid: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bill.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${MoneyFormatter.format(bill.amount)}, ${stringResource(R.string.money_bill_due_on, formatDate(bill.dueDate))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.money_bill_more, bill.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!bill.paid) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.money_bill_mark_paid)) }, onClick = {
                            menu = false
                            onMarkPaid()
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.money_bill_edit)) }, onClick = {
                        menu = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.money_bill_delete)) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
        if (bill.recurrence != BillRecurrence.NONE) {
            Text(
                stringResource(billRecurrenceLabel(bill.recurrence)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bill.paid) {
            StatusLine(Icons.Filled.CheckCircle, stringResource(R.string.money_bill_is_paid), MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (bill.dueDate.isBefore(today)) {
            StatusLine(Icons.Filled.Warning, stringResource(R.string.money_bill_overdue), MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
