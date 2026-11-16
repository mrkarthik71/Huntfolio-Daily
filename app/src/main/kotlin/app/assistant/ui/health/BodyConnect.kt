package app.assistant.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.port.BodySignals
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.consoleGround
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BodyUi(val connected: Boolean = false, val sleepMinutes: Int? = null, val steps: Long? = null)

@HiltViewModel
class BodyViewModel @Inject constructor(private val body: BodySignals) : ViewModel() {
    private val _ui = MutableStateFlow(BodyUi())
    val ui: StateFlow<BodyUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val on = body.connected()
            _ui.value = if (on) BodyUi(true, body.lastNightSleepMinutes(), body.stepsToday()) else BodyUi(false)
        }
    }
}

private fun hm(minutes: Int) = "${minutes / MIN_PER_HOUR}h ${minutes % MIN_PER_HOUR}m"

private const val MIN_PER_HOUR = 60

/** Sleep and steps tiles for Today; shows nothing until Health Connect is connected. */
@Composable
fun BodyTiles(modifier: Modifier = Modifier, viewModel: BodyViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (!ui.connected || (ui.sleepMinutes == null && ui.steps == null)) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReadoutTile(
            stringResource(R.string.hc_sleep),
            ui.sleepMinutes?.let(::hm) ?: "—",
            Modifier.weight(1f),
            note = if ((ui.sleepMinutes ?: Int.MAX_VALUE) < SHORT_NIGHT) stringResource(R.string.hc_short_night) else null,
            noteColor = MaterialTheme.colorScheme.primary,
        )
        ReadoutTile(stringResource(R.string.hc_steps), ui.steps?.let { "%,d".format(it) } ?: "—", Modifier.weight(1f))
    }
}

private const val SHORT_NIGHT = 6 * 60

/** The two read permissions Apex asks Health Connect for (the same set the reader checks). */
private val HC_PERMISSIONS = setOf(
    androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class),
    androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SleepSessionRecord::class),
)

/** Connect Health Connect: the reason first, then the system permission screen. */
@Composable
fun BodyConnectScreen(onBack: () -> Unit, viewModel: BodyViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { viewModel.refresh() }
    Column(Modifier.fillMaxSize().consoleGround().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
            Caption(stringResource(R.string.hc_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary, live = ui.connected)
        }
        Text(stringResource(R.string.hc_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Text(stringResource(R.string.hc_reason), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (ui.connected) {
            BodyTiles()
            Text(stringResource(R.string.hc_connected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        } else {
            CredPrimaryButton(stringResource(R.string.hc_connect), onClick = {
                runCatching { request.launch(HC_PERMISSIONS) }
            })
            Text(stringResource(R.string.hc_needs_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
