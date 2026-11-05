package app.assistant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.model.DataSource
import app.assistant.ui.common.DeleteEverythingDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAuditLog: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCalendars: () -> Unit,
    onDeleteEverything: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    /** Null while the money phase is off: the Money entry is then not shown. */
    onOpenMoney: (() -> Unit)? = null,
    onOpenAi: (() -> Unit)? = null,
    onOpenMail: (() -> Unit)? = null,
    onOpenTransfer: (() -> Unit)? = null,
    onOpenHealth: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(R.string.settings_sources) }
            items(state.sources, key = { it.source.name }) { row ->
                val status = stringResource(if (row.connected) R.string.source_connected else R.string.source_disconnected)
                ListItem(
                    headlineContent = { Text(stringResource(row.source.label())) },
                    supportingContent = { Text(status) },
                    // Status is shown as text and icon, not colour alone (Requirement 63.6).
                    trailingContent = {
                        Icon(if (row.connected) Icons.Filled.CheckCircle else Icons.Filled.Close, contentDescription = null)
                    },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader(R.string.settings_privacy) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_calendar_entry)) },
                    modifier = Modifier.clickable(onClick = onOpenCalendars),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notif_settings_entry)) },
                    modifier = Modifier.clickable(onClick = onOpenNotifications),
                )
            }
            if (onOpenMoney != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_money_entry)) },
                        modifier = Modifier.clickable(onClick = onOpenMoney),
                    )
                }
            }
            if (onOpenAi != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_ai_entry)) },
                        modifier = Modifier.clickable(onClick = onOpenAi),
                    )
                }
            }
            if (onOpenMail != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_mail_entry)) },
                        modifier = Modifier.clickable(onClick = onOpenMail),
                    )
                }
            }
            if (onOpenTransfer != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_transfer_entry)) },
                        modifier = Modifier.clickable(onClick = onOpenTransfer),
                    )
                }
            }
            if (onOpenHealth != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_health_entry)) },
                        modifier = Modifier.clickable(onClick = onOpenHealth),
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_audit_log)) },
                    modifier = Modifier.clickable(onClick = onOpenAuditLog),
                )
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.delete_everything), color = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { confirmDelete = true },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader(R.string.settings_about) }
            item {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_version, state.versionName, state.versionCode)) })
            }
        }
    }

    if (confirmDelete) {
        DeleteEverythingDialog(
            onConfirm = {
                confirmDelete = false
                onDeleteEverything()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SectionHeader(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

internal fun DataSource.label(): Int = when (this) {
    DataSource.SMS -> R.string.source_sms
    DataSource.APP_NOTIFICATIONS -> R.string.source_app_notifications
    DataSource.CALENDAR -> R.string.source_calendar
    DataSource.MAILBOX -> R.string.source_mailbox
    DataSource.AI_PROVIDER -> R.string.source_ai
    DataSource.HUNTFOLIO -> R.string.source_huntfolio
    DataSource.MANUAL -> R.string.source_manual
}
