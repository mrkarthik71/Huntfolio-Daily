package app.assistant.ui.life

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.model.FocusSession
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.ReflectionStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.Dial
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.consoleGround
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

@HiltViewModel
class FocusViewModel @Inject constructor(
    private val store: ReflectionStore,
    private val time: TimeSource,
    notify: NotifySwitch,
) : LifeViewModel(notify, NotificationType.FOCUS_END) {
    private val _running = MutableStateFlow<FocusSession?>(null)
    val running: StateFlow<FocusSession?> = _running.asStateFlow()

    /** Minutes of completed or stopped focus since midnight. */
    val todayMinutes: StateFlow<Int> = run {
        val start = time.today().atStartOfDay(time.zone()).toInstant()
        store.sessions(start, start.plus(Duration.ofDays(1))).map { list ->
            list.sumOf { s -> (s.endedAt?.let { Duration.between(s.startedAt, it).toMinutes() } ?: 0L).toInt() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), 0)
    }

    init {
        viewModelScope.launch { _running.value = store.running() }
    }

    fun nowMillis(): Long = time.now().toEpochMilli()

    /** Starting a block turns on its end alert, since the User asked for a timer. */
    fun start(title: String, minutes: Int) {
        replanAfter {
            if (!notifyOn.value) setNotify(true)
            store.start(title, null, time.now(), minutes)
            _running.value = store.running()
        }
    }

    fun stop(completed: Boolean) {
        val s = _running.value ?: return
        replanAfter {
            store.end(s.id, time.now(), completed)
            _running.value = null
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

private val LENGTHS = listOf(25, 50, 90)

@Composable
fun FocusScreen(onBack: () -> Unit, viewModel: FocusViewModel = hiltViewModel()) {
    val running by viewModel.running.collectAsStateWithLifecycle()
    val today by viewModel.todayMinutes.collectAsStateWithLifecycle()
    var minutes by rememberSaveable { mutableIntStateOf(LENGTHS[1]) }
    var title by rememberSaveable { mutableStateOf("") }
    var now by androidx.compose.runtime.remember { mutableLongStateOf(viewModel.nowMillis()) }
    LaunchedEffect(running) {
        while (running != null) {
            now = viewModel.nowMillis()
            val s = running!!
            if (now >= s.startedAt.toEpochMilli() + s.plannedMinutes * MS_PER_MIN) {
                viewModel.stop(completed = true)
                break
            }
            delay(TICK_MS)
        }
    }
    val defaultTitle = stringResource(R.string.focus_default_title)
    Column(Modifier.fillMaxSize().consoleGround().statusBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
            Caption(stringResource(R.string.focus_caption), Modifier.padding(start = 14.dp).weight(1f), color = MaterialTheme.colorScheme.primary, live = running != null)
        }
        val s = running
        if (s != null) {
            val total = s.plannedMinutes * MS_PER_MIN
            val left = (s.startedAt.toEpochMilli() + total - now).coerceAtLeast(0)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(s.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Dial(1f - left.toFloat() / total, clock(left), stringResource(R.string.focus_left), size = 240.dp)
            }
            CredPrimaryButton(stringResource(R.string.focus_finish), onClick = { viewModel.stop(completed = true) })
            TextButton(onClick = { viewModel.stop(completed = false) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.focus_stop), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
            LifeField(title, { title = it }, stringResource(R.string.focus_what))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENGTHS.forEach { m -> OptionKey(stringResource(R.string.focus_minutes, m), minutes == m, { minutes = m }) }
            }
            CredPrimaryButton(stringResource(R.string.focus_start), onClick = { viewModel.start(title.ifBlank { defaultTitle }, minutes) })
            Text(stringResource(R.string.focus_reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReadoutTile(stringResource(R.string.focus_today), stringResource(R.string.duration_h_m, today / MIN_PER_HOUR, today % MIN_PER_HOUR), Modifier.fillMaxWidth())
    }
}

private fun clock(ms: Long): String {
    val total = ms / MS_PER_SEC
    return "%02d:%02d".format(total / SEC_PER_MIN, total % SEC_PER_MIN)
}

private const val MS_PER_SEC = 1_000L
private const val SEC_PER_MIN = 60L
private const val MS_PER_MIN = 60_000L
private const val MIN_PER_HOUR = 60
private const val TICK_MS = 1_000L
