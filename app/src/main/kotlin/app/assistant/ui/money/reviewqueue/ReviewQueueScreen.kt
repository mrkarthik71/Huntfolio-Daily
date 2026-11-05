package app.assistant.ui.money.reviewqueue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.ReviewItem
import app.assistant.ui.common.DateField
import app.assistant.ui.common.errorText
import app.assistant.ui.common.formatDate
import app.assistant.ui.common.formatTime
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EmptyState
import java.time.LocalDate
import java.time.ZoneId

private const val MERCHANT_MAX = 100
private const val AMOUNT_MAX = 16
private const val MIN_TOUCH_DP = 48

/** The Review_Queue tab: messages the app could not read, newest first, each with a form that turns it into a Transaction. */
@Composable
fun ReviewQueueScreen(modifier: Modifier = Modifier, viewModel: ReviewQueueViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val zone = viewModel.zone()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.money_review_count, ui.count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
        if (!ui.loading && ui.items.isEmpty()) {
            EmptyState(stringResource(R.string.money_review_empty))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.items, key = { it.id.value }) { item ->
                    ReviewItemCard(
                        item = item,
                        zone = zone,
                        onSave = { form, onResult -> viewModel.save(item.id, form, onResult) },
                        onDismiss = { viewModel.dismiss(item.id) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/** The count of open items for the Money host's badge. It shows nothing when the queue is empty. */
@Composable
fun ReviewQueueCountBadge(modifier: Modifier = Modifier, viewModel: ReviewQueueCountViewModel = hiltViewModel()) {
    val count by viewModel.count.collectAsStateWithLifecycle()
    if (count > 0) {
        val description = stringResource(R.string.money_review_badge_cd, count)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.semantics { contentDescription = description },
        )
    }
}

@Composable
private fun ReviewItemCard(
    item: ReviewItem,
    zone: ZoneId,
    onSave: (ReviewForm, (FieldErrors<EditedField>?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val received = item.receivedAt.atZone(zone)
    CredCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.money_review_from, item.origin.value), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.money_review_received, formatDate(received.toLocalDate()) + " " + formatTime(received.toLocalTime())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(item.redactedText, style = MaterialTheme.typography.bodyMedium)
            if (open) {
                ReviewEntryForm(item = item, zone = zone, onSave = onSave, onClose = { open = false })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open = true }, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                        Text(stringResource(R.string.money_review_enter))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                        Text(stringResource(R.string.money_review_dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewEntryForm(item: ReviewItem, zone: ZoneId, onSave: (ReviewForm, (FieldErrors<EditedField>?) -> Unit) -> Unit, onClose: () -> Unit) {
    var type by remember { mutableStateOf<TxnType?>(null) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(item.receivedAt.atZone(zone).toLocalDate()) }
    var merchant by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(FieldErrors.none<EditedField>()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.money_review_type), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = type == TxnType.DEBIT,
                onClick = { type = TxnType.DEBIT },
                label = { Text(stringResource(R.string.money_review_debit)) },
                modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp),
            )
            FilterChip(
                selected = type == TxnType.CREDIT,
                onClick = { type = TxnType.CREDIT },
                label = { Text(stringResource(R.string.money_review_credit)) },
                modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp),
            )
        }
        FieldErrorText(errors[EditedField.TYPE])
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it.take(AMOUNT_MAX) },
            label = { Text(stringResource(R.string.money_review_amount)) },
            isError = errors[EditedField.AMOUNT] != null,
            supportingText = errors[EditedField.AMOUNT]?.let { { FieldErrorText(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField(
            label = stringResource(R.string.money_review_date),
            value = date,
            onChange = { date = it },
            clearable = false,
            error = errors[EditedField.DATE]?.let { stringResource(errorText(it)) },
        )
        OutlinedTextField(
            value = merchant,
            onValueChange = { merchant = it.take(MERCHANT_MAX) },
            label = { Text(stringResource(R.string.money_review_merchant)) },
            isError = errors[EditedField.MERCHANT] != null,
            supportingText = errors[EditedField.MERCHANT]?.let { { FieldErrorText(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!errors.isValid) {
            Text(stringResource(R.string.money_review_fix_fields), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(ReviewForm(type, amount, date, merchant)) { result -> errors = result ?: FieldErrors.none() } },
                modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp),
            ) { Text(stringResource(R.string.money_review_save)) }
            OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                Text(stringResource(R.string.money_review_close_form))
            }
        }
    }
}

/** An error as a warning icon plus text, so it never relies on colour alone. */
@Composable
private fun FieldErrorText(error: ValidationError?) {
    if (error == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(stringResource(errorText(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
