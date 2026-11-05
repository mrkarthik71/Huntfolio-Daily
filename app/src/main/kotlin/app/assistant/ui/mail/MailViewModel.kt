package app.assistant.ui.mail

import app.assistant.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MailError
import app.assistant.domain.port.MailQualitySummary
import app.assistant.domain.port.MailboxService
import app.assistant.domain.port.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row shown in the Inbox (no snippet, no links — only headers and scores, Requirement 54.7). */
data class InboxRow(val id: Long, val sender: String, val subject: String, val category: String, val finalScore: Int)

/** A Shortlist_Rule row in the editor. */
data class RuleRow(val id: Long, val senderContains: String, val subjectContains: String, val score: Int)

data class MailUi(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val testing: Boolean = false,
    @androidx.annotation.StringRes val testError: Int? = null,
    val importanceThreshold: Int = 6,
    val snippetLength: Int = 300,
    val syncIntervalMinutes: Int = 60,
    val rules: List<RuleRow> = emptyList(),
    val rulesEmpty: Boolean = false,
    val inbox: List<InboxRow> = emptyList(),
    /** Mail quality metrics (Requirement 55.4, 58), shown in a small card. */
    val wrongAlerts8w: Int = 0,
    val notifications8w: Int = 0,
    val avgNotificationsPerDay: Double = 0.0,
)

@HiltViewModel
class MailViewModel @Inject constructor(
    private val mailbox: MailboxService,
    private val settings: SettingsStore,
) : ViewModel() {

    private val connected = MutableStateFlow(false)
    private val testing = MutableStateFlow(false)
    private val testError = MutableStateFlow<Int?>(null)
    private val metrics = MutableStateFlow(MailQualitySummary(0, 0, 0.0))

    init {
        viewModelScope.launch {
            connected.value = mailbox.isConnected()
            metrics.value = mailbox.quality()
        }
    }

    val state: StateFlow<MailUi> =
        combine(
            settings.settings,
            mailbox.rules(),
            mailbox.inbox(),
            combine(connected, testing, testError) { c, t, e -> Triple(c, t, e) },
            metrics,
        ) { s, rules, items, conn, m ->
            MailUi(
                loading = false,
                connected = conn.first,
                testing = conn.second,
                testError = conn.third,
                importanceThreshold = s.mailImportanceThreshold,
                snippetLength = s.mailSnippetLength,
                syncIntervalMinutes = s.mailSyncIntervalMinutes,
                rules = rules.map { RuleRow(it.id, it.senderContains, it.subjectContains, it.score) },
                rulesEmpty = rules.isEmpty(),
                inbox = items.map { InboxRow(it.id, it.sender, it.subject, it.category, it.finalScore) },
                wrongAlerts8w = m.wrongAlerts8w,
                notifications8w = m.notifications8w,
                avgNotificationsPerDay = m.avgNotificationsPerDay,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MailUi())

    /** Tests the connection; credentials are stored only on success (Requirement 51.3, 51.5, 51.6). */
    fun connect(host: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            testing.value = true
            testError.value = null
            when (val r = mailbox.connect(host, port, username, password)) {
                is Outcome.Ok -> connected.value = true
                is Outcome.Err -> {
                    connected.value = false
                    testError.value = reason(r.error)
                }
            }
            testing.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            mailbox.disconnect()
            connected.value = false
        }
    }

    fun setThreshold(value: Int) {
        viewModelScope.launch { settings.update { it.copy(mailImportanceThreshold = value) } }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            if (settings.update { it.copy(mailSyncIntervalMinutes = minutes) } is Outcome.Ok) mailbox.rescheduleSync(minutes)
        }
    }

    fun addRule(senderContains: String, subjectContains: String, score: Int) {
        viewModelScope.launch { mailbox.addRule(senderContains, subjectContains, score) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { mailbox.deleteRule(id) }
    }

    /** "Not important": raises the sender's penalty and counts a Wrong_Mail_Alert once (Requirement 55.3, 55.5). */
    fun notImportant(row: InboxRow) {
        viewModelScope.launch { mailbox.markNotImportant(row.id, row.sender) }
    }

    private fun reason(error: MailError): Int = when (error) {
        MailError.AUTH_REJECTED -> R.string.mail_error_auth
        MailError.TIMEOUT -> R.string.mail_error_timeout
        MailError.TRANSPORT -> R.string.mail_error_transport
        MailError.FOLDER_GONE -> R.string.mail_error_folder
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
