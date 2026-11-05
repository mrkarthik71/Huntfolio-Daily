package app.assistant.data.mail

import android.content.Context
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.SenderPenaltyEntity
import app.assistant.data.db.entity.ShortlistRuleEntity
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MailError
import app.assistant.domain.port.MailConnector
import app.assistant.domain.port.MailInboxItem
import app.assistant.domain.port.MailQualitySummary
import app.assistant.domain.port.MailRule
import app.assistant.domain.port.MailboxService
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Room + IMAP implementation of [MailboxService]. Read-only: it never sends, moves or deletes mail. */
@Singleton
class DataMailboxService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val credentials: SecretMailCredentialStore,
    private val connector: MailConnector,
    private val settings: SettingsStore,
    private val quality: MailQuality,
    private val time: TimeSource,
) : MailboxService {

    private val dao get() = holder.database().mailDao()

    override fun rules(): Flow<List<MailRule>> =
        dao.rulesFlow().map { rows -> rows.map { MailRule(it.id, it.senderContains, it.subjectContains, it.score) } }

    override fun inbox(): Flow<List<MailInboxItem>> =
        dao.items().map { rows -> rows.map { MailInboxItem(it.id, it.sender, it.subject, it.category, it.finalScore) } }

    override suspend fun isConnected(): Boolean = credentials.hasCredentials()

    override suspend fun quality(): MailQualitySummary {
        val m = quality.metrics()
        return MailQualitySummary(m.wrongAlertsLast8Weeks, m.notificationsLast8Weeks, m.avgNotificationsPerDay)
    }

    override suspend fun connect(host: String, port: Int, username: String, password: String): Outcome<Unit, MailError> {
        // Stage the credentials so the connector's test uses them, then keep them only on success (Requirement 51.3, 51.5, 51.6).
        credentials.save(ImapConfig(host, port, username), password)
        return when (val r = connector.connectionTest()) {
            is Outcome.Ok -> {
                val cfg = settings.current()
                MailSyncWorker.schedule(context, cfg.mailSyncIntervalMinutes.toLong())
                MailDigestWorker.schedule(context, cfg.mailDigestTime.toSecondOfDay() / SECONDS_PER_MINUTE, time.now().atZone(time.zone()).toLocalTime())
                Outcome.Ok(Unit)
            }
            is Outcome.Err -> {
                // Rejected or unreachable: clear the staged credentials so sync does not retry a bad login.
                credentials.save(ImapConfig(host, port, username), "")
                Outcome.Err(r.error)
            }
        }
    }

    override suspend fun disconnect() {
        credentials.save(ImapConfig("", 0, ""), "")
        MailSyncWorker.cancel(context)
        MailDigestWorker.cancel(context)
    }

    override suspend fun rescheduleSync(intervalMinutes: Int) {
        if (isConnected()) MailSyncWorker.schedule(context, intervalMinutes.toLong())
    }

    override suspend fun addRule(senderContains: String, subjectContains: String, score: Int) {
        if (senderContains.isBlank() && subjectContains.isBlank()) return
        dao.addRule(ShortlistRuleEntity(senderContains = senderContains.trim(), subjectContains = subjectContains.trim(), score = score.coerceIn(1, MAX_SCORE)))
    }

    override suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    override suspend fun markNotImportant(itemId: Long, sender: String) {
        val current = dao.penalty(sender) ?: 0
        dao.putPenalty(SenderPenaltyEntity(sender, current + PENALTY_STEP))
        dao.markWrongAlert(itemId)
    }

    private companion object {
        const val PENALTY_STEP = 2
        const val MAX_SCORE = 10
        const val SECONDS_PER_MINUTE = 60
    }
}
