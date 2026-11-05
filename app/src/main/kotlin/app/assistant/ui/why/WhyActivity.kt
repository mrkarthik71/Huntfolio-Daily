package app.assistant.ui.why

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.assistant.R
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.LogEntry
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.SettingsStore
import app.assistant.ui.designsystem.AssistantTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Why am I seeing this?" (Requirement 18.6, 18.7). It names the rule and the setting behind a
 * notification and never shows user content, so it needs no App_Lock. It can switch the type off.
 */
@AndroidEntryPoint
class WhyActivity : ComponentActivity() {
    @Inject lateinit var log: NotificationLog

    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var scheduler: NotificationScheduling

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("log_id", -1L)
        setContent {
            AssistantTheme {
                var entry by remember { mutableStateOf<LogEntry?>(null) }
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(id) {
                    entry = if (id >= 0) log.get(id) else null
                    loaded = true
                }
                WhyScreen(
                    entry = entry,
                    loaded = loaded,
                    onTurnOff = { type ->
                        lifecycleScope.launch {
                            settings.update { it.copy(enabledTypes = it.enabledTypes - type) }
                            scheduler.refresh()
                            finish()
                        }
                    },
                    onClose = ::finish,
                )
            }
        }
    }
}

@Composable
fun WhyScreen(entry: LogEntry?, loaded: Boolean, onTurnOff: (NotificationType) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.why_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (entry == null) {
                if (loaded) Text(stringResource(R.string.why_unknown), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(stringResource(R.string.why_type, stringResource(typeName(entry.type))), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.why_rule, stringResource(ruleText(entry.ruleId))), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.why_setting), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { onTurnOff(entry.type) }) { Text(stringResource(R.string.why_turn_off)) }
            }
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
    }
}

internal fun typeName(type: NotificationType): Int = app.assistant.ui.settings.notificationTypeLabel(type)

internal fun ruleText(ruleId: String): Int = when (ruleId) {
    "daily.morning_brief" -> R.string.rule_morning_brief
    "daily.evening_review" -> R.string.rule_evening_review
    "daily.goal_nudge" -> R.string.rule_goal_nudge
    "snooze" -> R.string.rule_snooze
    else -> R.string.rule_unknown
}
