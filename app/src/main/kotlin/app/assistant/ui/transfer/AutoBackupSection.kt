package app.assistant.ui.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.backup.ExportPassphrase
import app.assistant.domain.port.AutoBackup
import app.assistant.domain.port.AutoBackupState
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.life.OptionKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class AutoBackupViewModel @Inject constructor(private val auto: AutoBackup, private val time: app.assistant.domain.port.TimeSource) : ViewModel() {
    fun zone(): java.time.ZoneId = time.zone()

    val state: StateFlow<AutoBackupState> = auto.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), AutoBackupState())
    private val _error = MutableStateFlow<ExportPassphrase.Error?>(null)
    val error: StateFlow<ExportPassphrase.Error?> = _error.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun enable(folder: String, pass: CharArray, repeat: CharArray, days: Int) {
        ExportPassphrase.validate(pass, repeat)?.let {
            _error.value = it
            return
        }
        _error.value = null
        viewModelScope.launch {
            auto.enable(folder, pass, days)
            pass.fill(' ')
            repeat.fill(' ')
            runNow()
        }
    }

    fun runNow() {
        viewModelScope.launch {
            _running.value = true
            auto.runNow()
            _running.value = false
        }
    }

    fun disable() {
        viewModelScope.launch { auto.disable() }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

private val INTERVALS = listOf(1, 7, 30)

/**
 * Automatic backup: the User picks a folder (on the phone, an SD card, or a cloud app's folder such
 * as Google Drive) and a passphrase; encrypted backups are written there on a schedule.
 */
@Composable
fun AutoBackupSection(viewModel: AutoBackupViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(s.everyDays) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.enable(it.toString(), pass.toCharArray(), repeat.toCharArray(), days) }
    }
    Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(stringResource(R.string.auto_caption), color = MaterialTheme.colorScheme.primary, live = s.enabled)
        if (s.enabled) {
            Text(
                stringResource(R.string.auto_on, s.folderLabel.orEmpty(), s.everyDays),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val last = s.lastAtEpochMs?.let {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(it).atZone(viewModel.zone()))
            }
            Text(
                when {
                    last == null -> stringResource(R.string.auto_never)
                    s.lastOk == true -> stringResource(R.string.auto_last_ok, last)
                    else -> stringResource(R.string.auto_last_failed, last)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.lastOk == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::runNow, enabled = !running) { Text(stringResource(if (running) R.string.auto_running else R.string.auto_now)) }
                TextButton(onClick = viewModel::disable) { Text(stringResource(R.string.auto_off), color = MaterialTheme.colorScheme.error) }
            }
        } else {
            Text(stringResource(R.string.auto_reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(pass, {
                pass = it
            }, label = {
                Text(stringResource(R.string.transfer_passphrase))
            }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(repeat, {
                repeat = it
            }, label = {
                Text(stringResource(R.string.transfer_passphrase_repeat))
            }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let {
                Text(
                    stringResource(
                        if (it ==
                            ExportPassphrase.Error.TOO_SHORT
                        ) {
                            R.string.auto_pass_short
                        } else {
                            R.string.auto_pass_mismatch
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVALS.forEach { d -> OptionKey(stringResource(R.string.auto_every, d), days == d, { days = d }) }
            }
            CredPrimaryButton(
                stringResource(R.string.auto_choose),
                onClick = { folderPicker.launch(null) },
                enabled =
                pass.length >= ExportPassphrase.MIN_LENGTH && pass == repeat,
            )
        }
    }
}
