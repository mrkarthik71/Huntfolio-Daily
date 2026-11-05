package app.assistant.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.domain.model.AiMode
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.ScreenTitle
import app.assistant.ui.designsystem.SectionHeader

/**
 * The AI settings section (Requirement 48.7): AI_Mode, the monthly cap with its spend and resume
 * date, the Cloud key (personal flavour only), and the on-device status. Nothing here contacts a
 * provider; it only edits settings and shows state.
 */
@Composable
fun AiSettingsScreen(viewModel: AiSettingsViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("AI advice")

        SectionHeader("Mode")
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiMode.entries.forEach { mode ->
                    ModeRow(mode = mode, selected = ui.mode == mode, onSelect = { viewModel.setMode(mode) })
                }
                Text(
                    "Off until you turn it on. Your plan and reviews work without AI; AI only rewords them.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader("Monthly cap")
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var cap by remember(ui.monthlyCapPaise) { mutableStateOf(if (ui.monthlyCapPaise > 0) (ui.monthlyCapPaise / PAISE).toString() else "") }
                androidx.compose.material3.OutlinedTextField(
                    value = cap,
                    onValueChange = { cap = it },
                    label = { Text("Cap in ₹ / month") },
                    isError = ui.capError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.TextButton(onClick = { viewModel.setCapRupees(cap) }) { Text("Save cap") }
                Text("Spent this month: ₹${ui.spentThisMonthPaise / PAISE}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (ui.capReached) {
                    Text("Cap reached. Cloud AI resumes on ${ui.resumeDate}.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (viewModel.keyEntrySupported) {
            SectionHeader("Cloud key")
            CredCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var key by remember { mutableStateOf("") }
                    androidx.compose.material3.OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(if (ui.keyConfigured) "Replace key" else "Enter key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.TextButton(onClick = { viewModel.setKey(key); key = "" }) {
                        Text(if (ui.keyConfigured) "Update key" else "Save key")
                    }
                    Text(if (ui.keyConfigured) "A key is stored." else "No key stored — Cloud mode is unavailable.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionHeader("On-device")
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    ui.onDeviceUnavailable?.let { "On-device model unavailable: ${it.lowercase().replace('_', ' ')}" } ?: "On-device model ready.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ModeRow(mode: AiMode, selected: Boolean, onSelect: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect)
        Text(
            when (mode) {
                AiMode.NONE -> "Off"
                AiMode.CLOUD -> "Cloud"
                AiMode.ON_DEVICE -> "On device"
            },
        )
    }
}

private const val PAISE = 100
