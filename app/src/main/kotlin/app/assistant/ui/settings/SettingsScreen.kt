package app.assistant.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.model.DataSource
import app.assistant.ui.common.DeleteEverythingDialog
import app.assistant.ui.designsystem.CredDivider
import app.assistant.ui.designsystem.CredGroup
import app.assistant.ui.designsystem.CredRow
import app.assistant.ui.designsystem.ScreenTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
    onOpenReminders: (() -> Unit)? = null,
    onOpenYear: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            androidx.compose.foundation.layout.Row(
                Modifier.padding(start = 16.dp, top = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    app.assistant.ui.designsystem.KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                }
                ScreenTitle(stringResource(R.string.settings_title))
            }
        }
        onOpenYear?.let { open ->
            item {
                CredGroup(null) {
                    CredRow(stringResource(R.string.year_entry), Icons.Outlined.Star, supporting = stringResource(R.string.year_entry_sub), onClick = open)
                }
            }
        }
        item {
            CredGroup(stringResource(R.string.settings_sources)) {
                state.sources.forEachIndexed { index, row ->
                    if (index > 0) CredDivider()
                    // Status is shown as text and icon, not colour alone (Requirement 63.6).
                    CredRow(
                        title = stringResource(row.source.label()),
                        icon = row.source.icon(),
                        supporting = stringResource(if (row.connected) R.string.source_connected else R.string.source_disconnected),
                        trailing = {
                            Icon(
                                if (row.connected) Icons.Filled.CheckCircle else Icons.Outlined.Close,
                                contentDescription = null,
                                tint = if (row.connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
        item {
            CredGroup(stringResource(R.string.settings_group_planner)) {
                CredRow(stringResource(R.string.settings_calendar_entry), Icons.Outlined.DateRange, onClick = onOpenCalendars)
                CredDivider()
                CredRow(stringResource(R.string.notif_settings_entry), Icons.Outlined.Notifications, onClick = onOpenNotifications)
                onOpenReminders?.let {
                    CredDivider()
                    CredRow(stringResource(R.string.reminders_entry), Icons.Outlined.LocationOn, onClick = it)
                }
            }
        }
        val connected = listOfNotNull(
            onOpenMoney?.let { Triple(R.string.settings_money_entry, Icons.Outlined.ShoppingCart, it) },
            onOpenAi?.let { Triple(R.string.settings_ai_entry, Icons.Outlined.Star, it) },
            onOpenMail?.let { Triple(R.string.settings_mail_entry, Icons.Outlined.Email, it) },
        )
        if (connected.isNotEmpty()) {
            item {
                CredGroup(stringResource(R.string.settings_group_connected)) {
                    connected.forEachIndexed { index, (label, icon, action) ->
                        if (index > 0) CredDivider()
                        CredRow(stringResource(label), icon, onClick = action)
                    }
                }
            }
        }
        item {
            CredGroup(stringResource(R.string.settings_group_data)) {
                onOpenTransfer?.let {
                    CredRow(stringResource(R.string.settings_transfer_entry), Icons.Outlined.Share, onClick = it)
                    CredDivider()
                }
                onOpenHealth?.let {
                    CredRow(stringResource(R.string.settings_health_entry), Icons.Outlined.Build, onClick = it)
                    CredDivider()
                }
                CredRow(stringResource(R.string.settings_audit_log), Icons.Outlined.Lock, onClick = onOpenAuditLog)
                CredDivider()
                CredRow(
                    stringResource(R.string.delete_everything),
                    Icons.Outlined.Delete,
                    destructive = true,
                    showChevron = false,
                    onClick = { confirmDelete = true },
                )
            }
        }
        item {
            CredGroup(stringResource(R.string.settings_about)) {
                CredRow(
                    stringResource(R.string.app_name),
                    Icons.Outlined.Info,
                    supporting = stringResource(R.string.settings_version, state.versionName, state.versionCode),
                )
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

internal fun DataSource.label(): Int = when (this) {
    DataSource.SMS -> R.string.source_sms
    DataSource.APP_NOTIFICATIONS -> R.string.source_app_notifications
    DataSource.CALENDAR -> R.string.source_calendar
    DataSource.MAILBOX -> R.string.source_mailbox
    DataSource.AI_PROVIDER -> R.string.source_ai
    DataSource.HUNTFOLIO -> R.string.source_huntfolio
    DataSource.MANUAL -> R.string.source_manual
}

internal fun DataSource.icon(): ImageVector = when (this) {
    DataSource.SMS -> Icons.Outlined.Email
    DataSource.APP_NOTIFICATIONS -> Icons.Outlined.Notifications
    DataSource.CALENDAR -> Icons.Outlined.DateRange
    DataSource.MAILBOX -> Icons.Outlined.Email
    DataSource.AI_PROVIDER -> Icons.Outlined.Star
    DataSource.HUNTFOLIO -> Icons.Outlined.AccountCircle
    DataSource.MANUAL -> Icons.Outlined.Edit
}
