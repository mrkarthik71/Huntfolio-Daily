package app.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.model.DataSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.raisedPanel

/** The sources the Today panel offers to connect, in order. */
private val QUICK_SOURCES = listOf(DataSource.SMS, DataSource.MAILBOX, DataSource.CALENDAR)

/**
 * "Connect your accounts" on Today: bank SMS, Gmail and the phone's calendar, each with a Connect
 * button that opens its settings page (where the reason is shown before any permission is asked).
 * The panel hides itself once all of them are connected.
 */
@Composable
fun ConnectSourcesPanel(onOpen: (DataSource) -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows = state.sources.filter { it.source in QUICK_SOURCES }.sortedBy { QUICK_SOURCES.indexOf(it.source) }
    if (rows.isEmpty() || rows.all { it.connected }) return
    Column(
        Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Caption(stringResource(R.string.connect_caption), color = MaterialTheme.colorScheme.primary)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(quickLabel(row.source)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        stringResource(if (row.connected) R.string.source_connected else quickHint(row.source)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PillButton(
                    stringResource(if (row.connected) R.string.connect_manage else R.string.connect_button),
                    onClick = { onOpen(row.source) },
                    container = if (row.connected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                    content = if (row.connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private fun quickLabel(source: DataSource): Int = when (source) {
    DataSource.SMS -> R.string.connect_sms
    DataSource.MAILBOX -> R.string.connect_gmail
    else -> R.string.connect_calendar
}

private fun quickHint(source: DataSource): Int = when (source) {
    DataSource.SMS -> R.string.connect_sms_hint
    DataSource.MAILBOX -> R.string.connect_gmail_hint
    else -> R.string.connect_calendar_hint
}
