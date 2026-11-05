package app.assistant.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.assistant.domain.engine.suggestions.SuggestionSnapshot
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.port.DueUsageCheck
import app.assistant.domain.port.ManualSubscriptionField
import app.assistant.domain.port.SubscriptionView
import app.assistant.ui.common.DateField
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.CredStat
import app.assistant.ui.designsystem.EmptyState
import app.assistant.ui.designsystem.HeroCard
import app.assistant.ui.designsystem.SectionHeader
import java.time.LocalDate

/** Label resource for a billing interval. */
internal fun intervalLabel(i: SubscriptionInterval): Int = when (i) {
    SubscriptionInterval.WEEKLY -> R.string.subs_interval_weekly
    SubscriptionInterval.MONTHLY -> R.string.subs_interval_monthly
    SubscriptionInterval.QUARTERLY -> R.string.subs_interval_quarterly
    SubscriptionInterval.YEARLY -> R.string.subs_interval_yearly
}

private fun fieldLabel(f: ManualSubscriptionField): Int = when (f) {
    ManualSubscriptionField.MERCHANT -> R.string.subs_error_merchant
    ManualSubscriptionField.AMOUNT -> R.string.subs_error_amount
    ManualSubscriptionField.INTERVAL -> R.string.subs_error_interval
    ManualSubscriptionField.NEXT_DUE -> R.string.subs_error_next_due
}

/**
 * The Subscriptions section of Money (Requirement 37.1, 37.2, 37.4, 37.7, 38.1, 38.3, 39.4, 50.1):
 * a hero with the yearly total (₹0 with none active), Suggestions, due usage checks answered with
 * one tap, proposed and active lists ordered by next due date, and the manual-add form.
 */
@Composable
fun SubscriptionsScreen(modifier: Modifier = Modifier, viewModel: SubscriptionsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroCard {
                CredStat(
                    caption = stringResource(R.string.subs_total_year),
                    value = MoneyFormatter.format(ui.totalYearlyCost),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.subs_active_count, ui.active.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item { ManualAddForm(today = viewModel::today, onSave = viewModel::addManual) }

        if (ui.suggestions.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.subs_suggestions).uppercase()) }
            items(ui.suggestions, key = { "sug-${it.id}" }) { s ->
                SuggestionCard(s, onAccept = { viewModel.acceptSuggestion(s.id) }, onDismiss = { viewModel.dismissSuggestion(s.id) })
            }
        }

        if (ui.dueUsageChecks.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.subs_usage_title).uppercase()) }
            items(ui.dueUsageChecks, key = { "use-${it.subscriptionId}" }) { check ->
                UsageCheckCard(check, onYes = { viewModel.answerUsage(check.subscriptionId, true) }, onNo = { viewModel.answerUsage(check.subscriptionId, false) })
            }
        }

        item { SectionHeader(stringResource(R.string.subs_proposed).uppercase()) }
        if (ui.proposals.isEmpty()) {
            item { EmptyState(stringResource(R.string.subs_proposed_empty)) }
        } else {
            items(ui.proposals, key = { "prop-${it.snapshot.id}" }) { sub ->
                ProposalCard(sub, onConfirm = { sub.snapshot.id?.let(viewModel::confirm) }, onReject = { sub.snapshot.id?.let(viewModel::reject) })
            }
        }

        item { SectionHeader(stringResource(R.string.subs_active).uppercase()) }
        if (ui.active.isEmpty()) {
            item { EmptyState(stringResource(R.string.subs_active_empty)) }
        } else {
            items(ui.active, key = { "act-${it.snapshot.id}" }) { sub -> ActiveCard(sub) }
        }
    }
}

@Composable
private fun SubscriptionSummary(sub: SubscriptionView) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(sub.merchant, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.subs_next_due, stringResource(intervalLabel(sub.snapshot.interval)), formatDate(sub.snapshot.nextDue)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(MoneyFormatter.format(sub.snapshot.amount), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ActiveCard(sub: SubscriptionView) {
    CredCard {
        SubscriptionSummary(sub)
        Text(
            stringResource(R.string.subs_yearly_cost, MoneyFormatter.format(sub.yearlyCost)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ProposalCard(sub: SubscriptionView, onConfirm: () -> Unit, onReject: () -> Unit) {
    CredCard {
        SubscriptionSummary(sub)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm) { Text(stringResource(R.string.subs_confirm)) }
            OutlinedButton(onClick = onReject) { Text(stringResource(R.string.subs_reject)) }
        }
    }
}

@Composable
private fun UsageCheckCard(check: DueUsageCheck, onYes: () -> Unit, onNo: () -> Unit) {
    CredCard {
        Text(stringResource(R.string.subs_usage_question, check.merchant, MoneyFormatter.format(check.amount)), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onYes) { Text(stringResource(R.string.subs_usage_yes)) }
            OutlinedButton(onClick = onNo) { Text(stringResource(R.string.subs_usage_no)) }
        }
    }
}

@Composable
private fun SuggestionCard(s: SuggestionSnapshot, onAccept: () -> Unit, onDismiss: () -> Unit) {
    CredCard {
        Text(s.text, style = MaterialTheme.typography.titleMedium)
        Text(s.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept) { Text(stringResource(R.string.subs_accept)) }
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.subs_dismiss)) }
        }
    }
}

/**
 * The manual-add form (Requirement 37.4, 37.7). Collapsed to one pill button until opened.
 * Merchant, amount in rupees, interval and next due; the repository rejects an empty merchant,
 * a zero amount or a past date and the failing fields are named. On success the form resets.
 */
@Composable
private fun ManualAddForm(
    today: () -> LocalDate,
    onSave: (String, Paise, SubscriptionInterval, LocalDate, (FieldErrors<ManualSubscriptionField>) -> Unit) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var merchant by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf(SubscriptionInterval.MONTHLY) }
    var nextDue by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var errorRes by rememberSaveable { mutableStateOf<List<Int>>(emptyList()) }

    if (!open) {
        CredPrimaryButton(stringResource(R.string.subs_add), onClick = {
            nextDue = nextDue ?: today()
            open = true
        })
        return
    }

    CredCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.subs_add), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it.take(MAX_MERCHANT) },
                label = { Text(stringResource(R.string.subs_merchant)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.take(MAX_AMOUNT) },
                label = { Text(stringResource(R.string.subs_amount)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(
                label = stringResource(R.string.subs_next_due_label),
                value = nextDue,
                onChange = { nextDue = it },
                clearable = false,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubscriptionInterval.entries.forEach { opt ->
                    FilterChip(selected = opt == interval, onClick = { interval = opt }, label = { Text(stringResource(intervalLabel(opt))) })
                }
            }
            errorRes.forEach { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val amount = parseRupees(amountText)
                    val due = nextDue
                    when {
                        amount == null -> errorRes = listOf(R.string.subs_error_amount)
                        due == null -> errorRes = listOf(R.string.subs_error_next_due)
                        else -> onSave(merchant, amount, interval, due) { errors ->
                            if (errors.isValid) {
                                open = false
                                merchant = ""
                                amountText = ""
                                nextDue = null
                                errorRes = emptyList()
                            } else {
                                errorRes = errors.errors.keys.map(::fieldLabel)
                            }
                        }
                    }
                }) { Text(stringResource(R.string.subs_save)) }
                OutlinedButton(onClick = {
                    open = false
                    errorRes = emptyList()
                }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

private const val MAX_MERCHANT = 60
private const val MAX_AMOUNT = 12

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
