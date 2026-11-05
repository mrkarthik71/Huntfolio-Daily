package app.assistant.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.port.LogEntry
import app.assistant.ui.settings.notificationTypeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationInboxScreen(onBack: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.inbox.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                app.assistant.ui.designsystem.EmptyState(stringResource(R.string.notif_inbox_empty))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(ui.inbox, key = { it.id }) { entry ->
                    ListItem(
                        overlineContent = { Text(stringResource(notificationTypeLabel(entry.type))) },
                        headlineContent = { Text(entry.summary) },
                        supportingContent = { Text(stringResource(reasonText(entry))) },
                    )
                }
            }
        }
    }
}

private fun reasonText(entry: LogEntry): Int = when {
    entry.ruleId == "blocked" -> R.string.notif_inbox_reason_blocked
    else -> R.string.notif_inbox_reason_cap
}
