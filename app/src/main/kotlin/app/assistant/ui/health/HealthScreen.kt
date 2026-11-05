package app.assistant.ui.health

import app.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.domain.engine.health.HealthRules
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.SectionHeader

/**
 * The combined health warning (Requirement 62.3): every current problem in one place with its fix,
 * the Battery_Guide steps, an open-settings action, and a dismiss that stays quiet until a new
 * problem appears. When there is nothing wrong it says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(onBack: () -> Unit, viewModel: HealthViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.health_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.problems.isEmpty() && !ui.loading) {
                CredCard { Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.health_ok), style = MaterialTheme.typography.bodyMedium) } }
                return@Column
            }

            SectionHeader(stringResource(R.string.health_attention).uppercase())
            ui.problems.forEach { problem ->
                CredCard { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(describe(problem)), style = MaterialTheme.typography.bodyMedium) } }
            }

            if (HealthRules.Problem.JOB_MISSED in ui.problems) {
                SectionHeader(stringResource(R.string.health_battery).uppercase())
                CredCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ui.batteryIsStandard) Text(stringResource(R.string.health_battery_steps), style = MaterialTheme.typography.bodySmall)
                        ui.batterySteps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { viewModel.openBatterySettings() }) { Text(stringResource(R.string.health_open_battery)) }
                    }
                }
            }

            TextButton(onClick = { viewModel.dismiss(onBack) }) { Text(stringResource(R.string.health_dismiss)) }
        }
    }
}

private fun describe(problem: HealthRules.Problem): Int = when (problem) {
    HealthRules.Problem.JOB_MISSED -> R.string.health_job_missed
    HealthRules.Problem.NOTIFICATIONS_BLOCKED -> R.string.health_notifications_blocked
    HealthRules.Problem.CHANNEL_BLOCKED -> R.string.health_channel_blocked
    HealthRules.Problem.PERMISSION_MISSING -> R.string.health_permission_missing
    HealthRules.Problem.NOTIFICATION_ACCESS_LOST -> R.string.health_notification_access
    HealthRules.Problem.MAILBOX_NEEDS_RECONNECT -> R.string.health_mailbox
}
