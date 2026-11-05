package app.assistant.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.assistant.domain.engine.suggestions.SuggestionSnapshot
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.port.DueUsageCheck
import app.assistant.domain.port.ManualSubscriptionField
import app.assistant.domain.port.SubscriptionView
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EmptyState
import app.assistant.ui.designsystem.HeroCard
import app.assistant.ui.designsystem.ScreenTitle
import app.assistant.ui.designsystem.SectionHeader
import java.time.LocalDate

/** ₹ amount from paise, with two decimals only when there are paise. */
internal fun rupees(p: Paise): String {
    val whole = p.value / 100
    val paise = p.value % 100
    return if (paise == 0L) "₹$whole" else "₹$whole.${paise.toString().padStart(2, '0')}"
}

internal fun intervalLabel(i: SubscriptionInterval): String = when (i) {
    SubscriptionInterval.WEEKLY -> "weekly"
    SubscriptionInterval.MONTHLY -> "monthly"
    SubscriptionInterval.QUARTERLY -> "quarterly"
    SubscriptionInterval.YEARLY -> "yearly"
}

/**
 * The Subscriptions screen (Requirement 37.1, 37.2, 37.4, 37.7, 38.1, 38.3, 39.4, 50.1). Proposed and active lists
 * ordered by next due date, total yearly cost (₹0 with none active), confirm/reject, due usage checks answered with one
 * tap, and Suggestion cards with accept/dismiss. The manual-add form is a separate route kept small here.
 */
@Composable
fun SubscriptionsScreen(viewModel: SubscriptionsViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Subscriptions") }

        item {
            HeroCard {
                Column(Modifier.padding(20.dp)) {
                    Text("Total a year")
                    Text(rupees(ui.totalYearlyCost))
                }
            }
        }

        item { ManualAddForm(onSave = viewModel::addManual) }

        if (ui.suggestions.isNotEmpty()) {
            item { SectionHeader("Suggestions") }
            items(ui.suggestions, key = { "sug-${it.id}" }) { s ->
                SuggestionCard(s, onAccept = { viewModel.acceptSuggestion(s.id) }, onDismiss = { viewModel.dismissSuggestion(s.id) })
            }
        }

        if (ui.dueUsageChecks.isNotEmpty()) {
            item { SectionHeader("Did you use these?") }
            items(ui.dueUsageChecks, key = { "use-${it.subscriptionId}" }) { check ->
                UsageCheckCard(check, onYes = { viewModel.answerUsage(check.subscriptionId, true) }, onNo = { viewModel.answerUsage(check.subscriptionId, false) })
            }
        }

        item { SectionHeader("Proposed") }
        if (ui.proposals.isEmpty()) {
            item { EmptyState("No proposed subscriptions. Confirm one when the app spots a repeating charge.") }
        } else {
            items(ui.proposals, key = { "prop-${it.snapshot.id}" }) { sub ->
                ProposalCard(sub, onConfirm = { sub.snapshot.id?.let(viewModel::confirm) }, onReject = { sub.snapshot.id?.let(viewModel::reject) })
            }
        }

        item { SectionHeader("Active") }
        if (ui.active.isEmpty()) {
            item { EmptyState("No active subscriptions yet.") }
        } else {
            items(ui.active, key = { "act-${it.snapshot.id}" }) { sub -> ActiveCard(sub) }
        }
    }
}

@Composable
private fun ActiveCard(sub: SubscriptionView) {
    CredCard {
        Column(Modifier.padding(16.dp)) {
            Text(sub.merchant)
            Text("${rupees(sub.snapshot.amount)} ${intervalLabel(sub.snapshot.interval)} · next ${sub.snapshot.nextDue}")
            Text("${rupees(sub.yearlyCost)} a year")
        }
    }
}

@Composable
private fun ProposalCard(sub: SubscriptionView, onConfirm: () -> Unit, onReject: () -> Unit) {
    CredCard {
        Column(Modifier.padding(16.dp)) {
            Text(sub.merchant)
            Text("${rupees(sub.snapshot.amount)} ${intervalLabel(sub.snapshot.interval)} · next ${sub.snapshot.nextDue}")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("Confirm") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun UsageCheckCard(check: DueUsageCheck, onYes: () -> Unit, onNo: () -> Unit) {
    CredCard {
        Column(Modifier.padding(16.dp)) {
            Text("${check.merchant} · ${rupees(check.amount)}")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onYes) { Text("Yes") }
                OutlinedButton(onClick = onNo) { Text("No") }
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: SuggestionSnapshot, onAccept: () -> Unit, onDismiss: () -> Unit) {
    CredCard {
        Column(Modifier.padding(16.dp)) {
            Text(s.text)
            Text(s.reason)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Accept") }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * The manual-add form (Requirement 37.4, 37.7). Collapsed to a button until opened. Merchant, amount (rupees), interval
 * and next-due; a past due date, empty merchant or zero amount is rejected with a field message (37.7). On success the
 * form clears and collapses.
 */
@Composable
private fun ManualAddForm(
    onSave: (String, Paise, SubscriptionInterval, LocalDate, (app.assistant.domain.error.FieldErrors<ManualSubscriptionField>) -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(SubscriptionInterval.MONTHLY) }
    var nextDueText by remember { mutableStateOf(LocalDate.now().toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!open) {
        Button(onClick = { open = true }) { Text("Add subscription") }
        return
    }

    CredCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("Merchant") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("Amount (₹)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = nextDueText, onValueChange = { nextDueText = it }, label = { Text("Next due (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionInterval.entries.forEach { opt ->
                    if (opt == interval) {
                        Button(onClick = { interval = opt }) { Text(intervalLabel(opt)) }
                    } else {
                        OutlinedButton(onClick = { interval = opt }) { Text(intervalLabel(opt)) }
                    }
                }
            }
            error?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val amount = parseRupees(amountText)
                    val due = runCatching { LocalDate.parse(nextDueText) }.getOrNull()
                    when {
                        amount == null -> error = "Enter a valid amount"
                        due == null -> error = "Enter a valid date (yyyy-MM-dd)"
                        else -> onSave(merchant, amount, interval, due) { errors ->
                            if (errors.isValid) {
                                open = false
                                merchant = ""
                                amountText = ""
                                error = null
                            } else {
                                error = errors.errors.keys.joinToString(", ") { it.name.lowercase() } + " invalid"
                            }
                        }
                    }
                }) { Text("Save") }
                OutlinedButton(onClick = { open = false; error = null }) { Text("Cancel") }
            }
        }
    }
}

/** Parses a rupee string (e.g. "149" or "149.50") to Paise, or null when it is not a valid non-negative amount. */
internal fun parseRupees(text: String): Paise? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(".")
    if (parts.size > 2) return null
    val whole = parts[0].toLongOrNull() ?: return null
    if (whole < 0) return null
    val paise = when {
        parts.size == 1 -> 0L
        parts[1].length == 1 -> (parts[1].toLongOrNull() ?: return null) * 10
        parts[1].length == 2 -> parts[1].toLongOrNull() ?: return null
        else -> return null
    }
    if (paise < 0) return null
    return runCatching { Paise(Math.addExact(Math.multiplyExact(whole, 100L), paise)) }.getOrNull()
}
