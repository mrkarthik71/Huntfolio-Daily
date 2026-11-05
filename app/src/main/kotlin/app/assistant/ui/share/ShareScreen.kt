package app.assistant.ui.share

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.ask.AskParser
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.model.SmartReminder
import app.assistant.domain.port.MemoryRepository
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.ReminderScheduler
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.recessedWell
import app.assistant.ui.life.OptionKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

enum class ShareTarget { TASK, NOTE, REMINDER }

@HiltViewModel
@Suppress("LongParameterList") // one store per thing shared text can become
class ShareViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val memory: MemoryRepository,
    private val planner: PlannerRepository,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsStore,
    private val time: TimeSource,
) : ViewModel() {
    private val _saved = MutableStateFlow<ShareTarget?>(null)
    val saved: StateFlow<ShareTarget?> = _saved.asStateFlow()

    fun save(text: String, target: ShareTarget) {
        val body = text.trim().take(MemoryRepository.MAX_LENGTH)
        if (body.isEmpty()) return
        viewModelScope.launch {
            when (target) {
                ShareTarget.TASK -> tasks.quickAdd(body.take(TASK_MAX), time.now(), time.today())
                ShareTarget.NOTE -> memory.add(body, time.now())
                ShareTarget.REMINDER -> {
                    val zone = time.zone()
                    val now = time.now().atZone(zone).toLocalDateTime()
                    val q = AskParser.parse("remind me to $body", now)
                    val at = q.remindAt ?: now.toLocalDate().plusDays(1).atTime(LocalTime.of(MORNING, 0))
                    planner.saveReminder(SmartReminder(ReminderId(0), q.text.ifBlank { body }.take(TASK_MAX), ReminderKind.TIME, fireAt = at.atZone(zone).toInstant()))
                    settings.update { it.copy(enabledTypes = it.enabledTypes + NotificationType.SMART_REMINDER) }
                    scheduler.sync()
                }
            }
            _saved.value = target
        }
    }

    private companion object {
        const val TASK_MAX = 200
        const val MORNING = 9
    }
}

/** What to do with text shared into Apex: a task, a memory note or a reminder. */
@Composable
fun ShareScreen(text: String, onDone: () -> Unit, viewModel: ShareViewModel = hiltViewModel()) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var target by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(ShareTarget.TASK) }
    Column(Modifier.fillMaxSize().consoleGround().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onDone)
            Caption(stringResource(R.string.share_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = PREVIEW_LINES,
            modifier = Modifier.fillMaxWidth().recessedWell(MaterialTheme.shapes.medium).padding(16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionKey(stringResource(R.string.share_as_task), target == ShareTarget.TASK, { target = ShareTarget.TASK })
            OptionKey(stringResource(R.string.share_as_note), target == ShareTarget.NOTE, { target = ShareTarget.NOTE })
            OptionKey(stringResource(R.string.share_as_reminder), target == ShareTarget.REMINDER, { target = ShareTarget.REMINDER })
        }
        if (saved != null) {
            Caption(stringResource(R.string.share_saved), color = MaterialTheme.colorScheme.tertiary)
            CredPrimaryButton(stringResource(R.string.share_done), onClick = onDone)
        } else {
            CredPrimaryButton(stringResource(R.string.life_save), onClick = { viewModel.save(text, target) })
        }
    }
}

private const val PREVIEW_LINES = 8
