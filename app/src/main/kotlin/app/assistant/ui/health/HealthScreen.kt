package app.assistant.ui.health

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
        topBar = { TopAppBar(title = { Text("App health") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.problems.isEmpty() && !ui.loading) {
                CredCard { Column(Modifier.padding(16.dp)) { Text("Everything is working. Background jobs, notifications and connected sources are healthy.", style = MaterialTheme.typography.bodyMedium) } }
                return@Column
            }

            SectionHeader("What needs attention")
            ui.problems.forEach { problem ->
                CredCard { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(describe(problem), style = MaterialTheme.typography.bodyMedium) } }
            }

            if (HealthRules.Problem.JOB_MISSED in ui.problems) {
                SectionHeader("Battery optimisation")
                CredCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ui.batteryIsStandard) Text("Follow these steps (generic Android):", style = MaterialTheme.typography.bodySmall)
                        ui.batterySteps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { viewModel.openBatterySettings() }) { Text("Open battery settings") }
                    }
                }
            }

            TextButton(onClick = { viewModel.dismiss(onBack) }) { Text("Dismiss") }
        }
    }
}

private fun describe(problem: HealthRules.Problem): String = when (problem) {
    HealthRules.Problem.JOB_MISSED -> "A background job hasn't run on time. Battery optimisation is the usual cause."
    HealthRules.Problem.NOTIFICATIONS_BLOCKED -> "Notifications are turned off for this app, so reminders won't show."
    HealthRules.Problem.CHANNEL_BLOCKED -> "A notification category is blocked. Re-enable it in system settings."
    HealthRules.Problem.PERMISSION_MISSING -> "A permission a feature needs was revoked."
    HealthRules.Problem.NOTIFICATION_ACCESS_LOST -> "Notification access was turned off, so app notifications can't be read."
    HealthRules.Problem.MAILBOX_NEEDS_RECONNECT -> "Your mailbox login was rejected. Reconnect it in Mail settings."
}
