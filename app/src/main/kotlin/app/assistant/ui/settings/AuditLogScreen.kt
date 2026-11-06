package app.assistant.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.model.AuditEntry
import app.assistant.domain.model.AuditKind
import app.assistant.ui.designsystem.CredTopBar
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Every Audit_Log entry, newest first, with every field including full payloads (Requirement 7.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(onBack: () -> Unit, viewModel: AuditLogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            CredTopBar(
                title = { Text(stringResource(R.string.audit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding)) {
                app.assistant.ui.designsystem.EmptyState(stringResource(R.string.audit_empty))
            }
            else -> {
                val zone = viewModel.zone()
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(list, key = { it.id.value }) { entry ->
                        AuditRow(entry, zone)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

@Composable
private fun AuditRow(e: AuditEntry, zone: ZoneId) {
    val ok = stringResource(R.string.audit_success)
    val failed = stringResource(R.string.audit_failed)
    fun result(s: Boolean?) = when (s) {
        true -> ok
        false -> failed
        null -> "-"
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(e.at.atZone(zone).format(timeFormat), style = MaterialTheme.typography.labelMedium)
        val title = when (e.kind) {
            AuditKind.READ -> stringResource(
                R.string.audit_read,
                e.source?.let { stringResource(it.label()) } ?: "-",
                e.itemCount ?: 0,
                result(e.success),
            )
            AuditKind.AI_SEND -> stringResource(R.string.audit_ai_send, e.aiMode?.name ?: "-", e.promptType?.name ?: "-")
            AuditKind.AI_RESULT -> stringResource(R.string.audit_ai_result, result(e.success))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge)
        e.payload?.let { payload ->
            Text(stringResource(R.string.audit_payload), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(payload, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
