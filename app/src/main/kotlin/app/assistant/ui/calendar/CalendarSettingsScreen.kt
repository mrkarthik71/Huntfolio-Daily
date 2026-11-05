package app.assistant.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R

/**
 * The calendar-selection list (Requirement 22.1, 22.8). When the calendar permission is not
 * granted it shows the rationale and a grant button; once granted it lists every device calendar
 * with its display and account name and a switch, all selected at first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(onBack: () -> Unit, viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadIfGranted() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_settings_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MinuteStepper(
                label = stringResource(R.string.calendar_min_focus),
                minutes = ui.minFocusMinutes,
                step = FOCUS_STEP,
                onAdjust = viewModel::adjustMinFocus,
            )
            MinuteStepper(
                label = stringResource(R.string.calendar_event_lead),
                minutes = ui.eventLeadMinutes,
                step = LEAD_STEP,
                onAdjust = viewModel::adjustEventLead,
            )
            if (!ui.granted) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.calendar_rationale), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { viewModel.grantAndLoad() }) { Text(stringResource(R.string.calendar_grant)) }
                }
            } else if (ui.calendars.isEmpty()) {
                app.assistant.ui.designsystem.EmptyState(stringResource(R.string.calendar_none))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(ui.calendars, key = { it.info.id }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.info.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(row.info.accountName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = row.selected, onCheckedChange = { viewModel.toggle(row.info.id, it) })
                        }
                    }
                }
            }
        }
    }
}

private const val FOCUS_STEP = 15
private const val LEAD_STEP = 5

/** A label with the current value in minutes and minus/plus buttons; out-of-range changes are rejected by the store. */
@Composable
private fun MinuteStepper(label: String, minutes: Int, step: Int, onAdjust: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onAdjust(-step) }) { Text(stringResource(R.string.calendar_minus)) }
        Text(stringResource(R.string.calendar_minutes, minutes), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onAdjust(step) }) { Text(stringResource(R.string.calendar_plus)) }
    }
}
