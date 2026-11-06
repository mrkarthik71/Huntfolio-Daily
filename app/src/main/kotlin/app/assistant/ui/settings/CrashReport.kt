package app.assistant.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.assistant.R
import app.assistant.domain.port.CrashReports
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.recessedWell
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CrashReportViewModel @Inject constructor(private val reports: CrashReports) : ViewModel() {
    fun last(): String? = reports.last()

    fun clear() = reports.clear()
}

/** Shows the last recorded crash so the User can copy it into a bug report. */
@Composable
@Suppress("DEPRECATION") // LocalClipboardManager: plain-text copy is all this needs
fun CrashReportScreen(onBack: () -> Unit, viewModel: CrashReportViewModel = hiltViewModel()) {
    var report by remember { mutableStateOf(viewModel.last()) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().consoleGround().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
            Caption(stringResource(R.string.crash_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Text(stringResource(R.string.crash_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val text = report
        if (text == null) {
            Text(stringResource(R.string.crash_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SelectionContainer(Modifier.fillMaxWidth().recessedWell(MaterialTheme.shapes.medium).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
            }
            CredPrimaryButton(stringResource(if (copied) R.string.crash_copied else R.string.crash_copy), onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            })
            TextButton(onClick = {
                viewModel.clear()
                report = null
            }) { Text(stringResource(R.string.crash_clear)) }
        }
    }
}
