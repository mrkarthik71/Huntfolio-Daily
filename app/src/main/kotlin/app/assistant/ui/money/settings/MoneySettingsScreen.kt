package app.assistant.ui.money.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.quality.AccuracyValue
import app.assistant.domain.port.AddAppResult
import app.assistant.domain.port.AddSenderResult
import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.MoneyReaderState
import app.assistant.domain.port.SenderRules
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.CredTopBar
import app.assistant.ui.designsystem.SectionHeader

private const val MIN_TOUCH_DP = 48
private const val SENDER_MAX = 20

/** Test tags for the controls the UI tests drive. */
const val SENDER_INPUT_TAG = "money_settings_sender_input"

/**
 * The money settings: SMS reading with its rationale and history import, the Bank_Sender_List, notification access with
 * the Money_App_List, and the parse accuracy per origin (Requirements 26, 27, 36.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneySettingsScreen(onBack: () -> Unit, viewModel: MoneySettingsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    // Back from Android's notification access screen: read the access again (Requirement 27.5).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotifications() }

    Scaffold(
        topBar = {
            CredTopBar(
                title = { Text(stringResource(R.string.money_settings_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SmsSection(ui.readers.sms, ui.readers.historyImport, viewModel) }
            item { SectionHeader(stringResource(R.string.money_settings_senders_header)) }
            item {
                Text(
                    stringResource(R.string.money_settings_senders_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ui.senders, key = { "sender-" + it.entry.coreHeader }) { row ->
                SenderRowItem(row.entry.coreHeader, row.builtin, onRemove = { viewModel.removeSender(row.entry.coreHeader) })
            }
            item { AddSenderForm(ui.messages.sender, viewModel) }
            item { NotificationSection(ui.readers.notifications, viewModel) }
            item { SectionHeader(stringResource(R.string.money_settings_apps_header)) }
            item {
                Text(
                    stringResource(R.string.money_settings_apps_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.messages.app == AddAppResult.INVALID) item { ErrorLine(stringResource(R.string.money_settings_apps_invalid)) }
            if (!ui.loading && ui.apps.isEmpty()) item { Text(stringResource(R.string.money_settings_apps_empty)) }
            items(ui.apps, key = { "app-" + it.packageName }) { appRow ->
                SwitchRow(
                    label = appRow.label,
                    supporting = appRow.packageName,
                    checked = appRow.listed,
                    onToggle = { viewModel.setApp(appRow.packageName, it) },
                )
            }
            item { SectionHeader(stringResource(R.string.money_settings_accuracy_header)) }
            if (ui.accuracy.isEmpty()) item { Text(stringResource(R.string.money_settings_accuracy_empty)) }
            items(ui.accuracy, key = { "accuracy-" + it.origin }) { row ->
                AccuracyLine(row.origin, row.value)
            }
        }
    }
}

@Composable
private fun SmsSection(state: MoneyReaderState, importState: HistoryImportState, viewModel: MoneySettingsViewModel) {
    val context = LocalContext.current
    var rationale by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    // Android asks for the permission only after the User confirms the rationale (Requirement 26.1).
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_SMS] == true) viewModel.enableSms() else blocked = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.money_settings_sms_header))
        if (state == MoneyReaderState.NOT_AVAILABLE) {
            Text(stringResource(R.string.money_settings_sms_unavailable), style = MaterialTheme.typography.bodyLarge)
        } else {
            SwitchRow(
                label = stringResource(R.string.money_settings_sms_switch),
                supporting = stringResource(stateLabel(state)),
                checked = state != MoneyReaderState.OFF,
                onToggle = { on -> if (on) rationale = true else viewModel.disableSms() },
            )
            if (state == MoneyReaderState.NEEDS_PERMISSION) {
                RestrictedHint(onAsk = { rationale = true }, onOpen = { openAppInfo(context) })
            }
            if (state == MoneyReaderState.ACTIVE) {
                HistoryImportCard(importState, onStart = viewModel::runHistoryImport, onSkip = viewModel::declineHistoryImport)
            }
        }
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(R.string.money_settings_sms_rationale_title)) },
            text = { Text(stringResource(R.string.money_settings_sms_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    rationale = false
                    if (hasSms(context)) {
                        viewModel.enableSms()
                    } else {
                        runCatching { request.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }
                            .onFailure { blocked = true }
                    }
                }) { Text(stringResource(R.string.money_settings_sms_rationale_continue)) }
            },
            dismissButton = { TextButton(onClick = { rationale = false }) { Text(stringResource(R.string.money_settings_sms_rationale_cancel)) } },
        )
    }
    if (blocked) {
        AlertDialog(
            onDismissRequest = { blocked = false },
            title = { Text(stringResource(R.string.money_settings_blocked_title)) },
            text = { Text(stringResource(R.string.money_settings_restricted_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    blocked = false
                    openAppInfo(context)
                }) { Text(stringResource(R.string.money_settings_open_app_info)) }
            },
            dismissButton = { TextButton(onClick = { blocked = false }) { Text(stringResource(R.string.money_settings_sms_rationale_cancel)) } },
        )
    }
}

/** Sideloaded apps on Android 13+ must have "restricted settings" allowed before SMS or notification access can be granted. */
@Composable
private fun RestrictedHint(onAsk: (() -> Unit)?, onOpen: () -> Unit) {
    Text(
        stringResource(R.string.money_settings_restricted_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onAsk != null) {
            Button(onClick = onAsk, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) { Text(stringResource(R.string.money_settings_ask_again)) }
        }
        OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
            Text(stringResource(R.string.money_settings_open_app_info))
        }
    }
}

private fun hasSms(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun HistoryImportCard(state: HistoryImportState, onStart: () -> Unit, onSkip: () -> Unit) {
    when (state) {
        HistoryImportState.None, HistoryImportState.Offered -> CredCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.money_settings_import_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.money_settings_import_body), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                        Text(stringResource(R.string.money_settings_import_start))
                    }
                    if (state == HistoryImportState.Offered) {
                        OutlinedButton(onClick = onSkip, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                            Text(stringResource(R.string.money_settings_import_skip))
                        }
                    }
                }
            }
        }
        HistoryImportState.Running -> Text(stringResource(R.string.money_settings_import_running), style = MaterialTheme.typography.bodyMedium)
        is HistoryImportState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    R.string.money_settings_import_done,
                    state.summary.smsRead,
                    state.summary.transactionsCreated,
                    state.summary.reviewItems,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onStart, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                Text(stringResource(R.string.money_settings_import_again))
            }
        }
    }
}

@Composable
private fun SenderRowItem(coreHeader: String, builtin: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(coreHeader, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (builtin) R.string.money_settings_sender_builtin else R.string.money_settings_sender_custom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!builtin) {
            IconButton(onClick = onRemove, modifier = Modifier.size(MIN_TOUCH_DP.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.money_settings_sender_remove, coreHeader))
            }
        }
    }
}

@Composable
private fun AddSenderForm(message: AddSenderResult?, viewModel: MoneySettingsViewModel) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(SENDER_MAX + 1)
                viewModel.clearSenderMessage()
            },
            label = { Text(stringResource(R.string.money_settings_sender_input)) },
            isError = message != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(SENDER_INPUT_TAG),
        )
        when (message) {
            AddSenderResult.INVALID -> ErrorLine(stringResource(R.string.money_settings_sender_invalid))
            AddSenderResult.ALREADY_LISTED -> ErrorLine(stringResource(R.string.money_settings_sender_duplicate))
            AddSenderResult.ADDED, null -> Unit
        }
        Button(
            onClick = {
                viewModel.addSender(text)
                if (SenderRules.normalise(text) != null) text = ""
            },
            modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp),
        ) { Text(stringResource(R.string.money_settings_sender_add)) }
    }
}

@Composable
private fun NotificationSection(state: MoneyReaderState, viewModel: MoneySettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.money_settings_notifications_header))
        Text(stringResource(R.string.money_settings_notifications_explain), style = MaterialTheme.typography.bodyMedium)
        SwitchRow(
            label = stringResource(R.string.money_settings_notifications_switch),
            supporting = stringResource(stateLabel(state)),
            checked = state != MoneyReaderState.OFF && state != MoneyReaderState.NOT_AVAILABLE,
            onToggle = viewModel::setNotifications,
        )
        if (state == MoneyReaderState.NEEDS_PERMISSION || state == MoneyReaderState.DISCONNECTED) {
            Button(onClick = viewModel::openNotificationAccessSettings, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                Text(stringResource(R.string.money_settings_notifications_open))
            }
            val context = LocalContext.current
            RestrictedHint(onAsk = null, onOpen = { openAppInfo(context) })
        }
    }
}

@Composable
private fun AccuracyLine(origin: String, value: AccuracyValue) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(origin, style = MaterialTheme.typography.bodyLarge)
        Text(
            when (value) {
                AccuracyValue.NoData -> stringResource(R.string.money_settings_accuracy_none)
                is AccuracyValue.Percent -> stringResource(R.string.money_settings_accuracy_value, value.value.toPlainString())
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A row that toggles as a whole, so the label is the touch target. */
@Composable
private fun SwitchRow(label: String, supporting: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_DP.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** An error as a warning icon plus text, so it never relies on colour alone. */
@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@StringRes
private fun stateLabel(state: MoneyReaderState): Int = when (state) {
    MoneyReaderState.NOT_AVAILABLE -> R.string.money_settings_state_not_available
    MoneyReaderState.OFF -> R.string.money_settings_state_off
    MoneyReaderState.NEEDS_PERMISSION -> R.string.money_settings_state_needs_permission
    MoneyReaderState.ACTIVE -> R.string.money_settings_state_active
    MoneyReaderState.DISCONNECTED -> R.string.money_settings_state_disconnected
}
