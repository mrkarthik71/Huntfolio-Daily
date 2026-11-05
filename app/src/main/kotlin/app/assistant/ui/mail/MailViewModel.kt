package app.assistant.ui.mail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.SenderPenaltyEntity
import app.assistant.data.db.entity.ShortlistRuleEntity
import app.assistant.data.mail.ImapConfig
import app.assistant.data.mail.MailSyncWorker
import app.assistant.data.mail.SecretMailCredentialStore
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MailError
import app.assistant.domain.port.MailConnector
import app.assistant.domain.port.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    val testError: String? = null,
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
    private val holder: DatabaseHolder,
    private val credentials: SecretMailCredentialStore,
    private val connector: MailConnector,
    private val settings: SettingsStore,
    private val quality: app.assistant.data.mail.MailQuality,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val dao get() = holder.database().mailDao()
    private val connected = MutableStateFlow(false)
    private val testing = MutableStateFlow(false)
    private val testError = MutableStateFlow<String?>(null)
    private val metrics = MutableStateFlow(app.assistant.data.mail.MailQuality.Metrics(0, 0, 0, 0, 0.0))

    init {
        viewModelScope.launch {
            connected.value = credentials.hasCredentials()
            metrics.value = quality.metrics()
        }
    }

    val state: StateFlow<MailUi> =
        combine(
            settings.settings,
            dao.rulesFlow(),
            dao.items(),
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
                wrongAlerts8w = m.wrongAlertsLast8Weeks,
                notifications8w = m.notificationsLast8Weeks,
                avgNotificationsPerDay = m.avgNotificationsPerDay,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUi())

    /** Tests the connection, stores credentials only on success (Requirement 51.3, 51.5, 51.6). */
    fun connect(host: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            testing.value = true
            testError.value = null
            // Stage credentials so the connector's test uses them, then confirm.
            credentials.save(ImapConfig(host, port, username), password)
            when (val r = connector.connectionTest()) {
                is Outcome.Ok -> {
                    connected.value = true
                    val cfg = settings.current()
                    MailSyncWorker.schedule(context, cfg.mailSyncIntervalMinutes.toLong())
                    app.assistant.data.mail.MailDigestWorker.schedule(context, cfg.mailDigestTime.toSecondOfDay() / SECONDS_PER_MINUTE)
                }
                is Outcome.Err -> {
                    // Rejected or unreachable: clear the staged credentials so sync does not retry a bad login.
                    credentials.save(ImapConfig(host, port, username), "")
                    connected.value = false
                    testError.value = reason(r.error)
                }
            }
            testing.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            credentials.save(ImapConfig("", 0, ""), "")
            connected.value = false
            MailSyncWorker.cancel(context)
            app.assistant.data.mail.MailDigestWorker.cancel(context)
        }
    }

    fun setThreshold(value: Int) {
        viewModelScope.launch { settings.update { it.copy(mailImportanceThreshold = value) } }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            if (settings.update { it.copy(mailSyncIntervalMinutes = minutes) } is Outcome.Ok && connected.value) {
                MailSyncWorker.schedule(context, minutes.toLong())
            }
        }
    }

    fun addRule(senderContains: String, subjectContains: String, score: Int) {
        if (senderContains.isBlank() && subjectContains.isBlank()) return
        viewModelScope.launch { dao.addRule(ShortlistRuleEntity(senderContains = senderContains.trim(), subjectContains = subjectContains.trim(), score = score.coerceIn(1, MAX_SCORE))) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { dao.deleteRule(id) }
    }

    /** "Not important": raises the sender's penalty and counts a Wrong_Mail_Alert once (Requirement 55.3, 55.5). */
    fun notImportant(row: InboxRow) {
        viewModelScope.launch {
            val current = dao.penalty(row.sender) ?: 0
            dao.putPenalty(SenderPenaltyEntity(row.sender, current + PENALTY_STEP))
            dao.markWrongAlert(row.id)
        }
    }

    private fun reason(error: MailError): String = when (error) {
        MailError.AUTH_REJECTED -> "Login was rejected. Check the app password."
        MailError.TIMEOUT -> "The server did not respond in time."
        MailError.TRANSPORT -> "Could not reach the mail server."
        MailError.FOLDER_GONE -> "The mailbox folder was not found."
    }

    private companion object {
        const val PENALTY_STEP = 2
        const val MAX_SCORE = 10
        const val SECONDS_PER_MINUTE = 60
    }
}
