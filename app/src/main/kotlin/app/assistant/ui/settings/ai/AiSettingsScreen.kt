package app.assistant.ui.settings.ai

import app.assistant.R
import androidx.compose.ui.res.stringResource
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
        ScreenTitle(stringResource(R.string.ai_title))

        SectionHeader(stringResource(R.string.ai_mode).uppercase())
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiMode.entries.forEach { mode ->
                    ModeRow(mode = mode, selected = ui.mode == mode, onSelect = { viewModel.setMode(mode) })
                }
                Text(
                    stringResource(R.string.ai_mode_body),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader(stringResource(R.string.ai_cap).uppercase())
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var cap by remember(ui.monthlyCapPaise) { mutableStateOf(if (ui.monthlyCapPaise > 0) (ui.monthlyCapPaise / PAISE).toString() else "") }
                androidx.compose.material3.OutlinedTextField(
                    value = cap,
                    onValueChange = { cap = it },
                    label = { Text(stringResource(R.string.ai_cap_label)) },
                    isError = ui.capError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.TextButton(onClick = { viewModel.setCapRupees(cap) }) { Text(stringResource(R.string.ai_cap_save)) }
                Text(stringResource(R.string.ai_spent, ui.spentThisMonthPaise / PAISE), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (ui.capReached) {
                    Text(stringResource(R.string.ai_cap_reached, ui.resumeDate.toString()), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (viewModel.keyEntrySupported) {
            SectionHeader(stringResource(R.string.ai_key).uppercase())
            CredCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var key by remember { mutableStateOf("") }
                    androidx.compose.material3.OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(if (ui.keyConfigured) R.string.ai_key_replace else R.string.ai_key_enter)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.TextButton(onClick = { viewModel.setKey(key); key = "" }) {
                        Text(stringResource(if (ui.keyConfigured) R.string.ai_key_update else R.string.ai_key_save))
                    }
                    Text(stringResource(if (ui.keyConfigured) R.string.ai_key_stored else R.string.ai_key_missing), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionHeader(stringResource(R.string.ai_ondevice).uppercase())
        CredCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    ui.onDeviceUnavailable?.let { stringResource(R.string.ai_ondevice_unavailable, it.lowercase().replace('_', ' ')) } ?: stringResource(R.string.ai_ondevice_ready),
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
                AiMode.NONE -> stringResource(R.string.ai_mode_off)
                AiMode.CLOUD -> stringResource(R.string.ai_mode_cloud)
                AiMode.ON_DEVICE -> stringResource(R.string.ai_mode_ondevice)
            },
        )
    }
}

private const val PAISE = 100
