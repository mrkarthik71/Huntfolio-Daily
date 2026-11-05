package app.assistant.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.settings.SettingsValidator
import app.assistant.ui.common.TimeField
import app.assistant.ui.common.formatTime
import app.assistant.ui.settings.notificationTypeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, onOpenInbox: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val s = ui.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (!ui.hasExactAlarmAccess) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.notif_exact_alarm_notice), style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = viewModel::openExactAlarmSettings,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text(stringResource(R.string.notif_exact_alarm_action))
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader(R.string.notif_section_types) }
            PHASE1_TYPES.forEach { type ->
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(notificationTypeLabel(type))) },
                        trailingContent = {
                            Switch(
                                checked = type in s.enabledTypes,
                                onCheckedChange = { viewModel.toggleType(type, it) },
                            )
                        },
                    )
                }
            }
            if (app.assistant.app.FeatureFlags.CALENDAR) {
                PHASE2_TYPES.forEach { type ->
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(notificationTypeLabel(type))) },
                            trailingContent = {
                                Switch(
                                    checked = type in s.enabledTypes,
                                    onCheckedChange = { viewModel.toggleType(type, it) },
                                )
                            },
                        )
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionHeader(R.string.notif_section_times) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notif_morning_time)) },
                    trailingContent = {
                        TimeField(label = formatTime(s.morningBrief), value = s.morningBrief, onChange = viewModel::setMorningTime)
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notif_evening_time)) },
                    trailingContent = {
                        TimeField(label = formatTime(s.eveningReview), value = s.eveningReview, onChange = viewModel::setEveningTime)
                    },
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader(R.string.notif_section_limits) }
            item { DailyCapRow(cap = s.dailyCap, onChange = viewModel::setDailyCap) }

            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notif_inbox_entry)) },
                    modifier = Modifier.clickable(onClick = onOpenInbox),
                )
            }
        }
    }
}

@Composable
private fun DailyCapRow(cap: Int, onChange: (Int) -> Unit) {
    var local by rememberSaveable(cap) { mutableStateOf(cap.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.notif_daily_cap, local.toInt()))
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.toInt()) },
            valueRange = SettingsValidator.CAP_RANGE.first.toFloat()..SettingsValidator.CAP_RANGE.last.toFloat(),
            steps = SettingsValidator.CAP_RANGE.last - SettingsValidator.CAP_RANGE.first - 1,
        )
        Text(stringResource(R.string.notif_daily_cap_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
