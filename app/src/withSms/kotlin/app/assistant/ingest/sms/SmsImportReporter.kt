package app.assistant.ingest.sms

import app.assistant.domain.port.HistoryImportState
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A process-wide holder so the history-import worker can publish its progress and final counts, and the money settings
 * can show them (Requirement 26.5). It holds counts only — never Message_Text. withSms build only.
 */
@Singleton
class SmsImportReporter @Inject constructor() {
    val state: MutableStateFlow<HistoryImportState> = MutableStateFlow(HistoryImportState.None)
}
