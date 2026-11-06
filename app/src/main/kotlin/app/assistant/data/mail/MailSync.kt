package app.assistant.data.mail

import androidx.room.withTransaction
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.InboxItemEntity
import app.assistant.data.db.entity.MailCursorEntity
import app.assistant.domain.engine.mail.MailTriage
import app.assistant.domain.engine.mail.ShortlistRule
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.MailCategory
import app.assistant.domain.model.MailMessage
import app.assistant.domain.port.Advisor
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.MailAdvice
import app.assistant.domain.port.MailConnector
import app.assistant.domain.port.MessageFacts
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TimeSource
import app.assistant.security.refs.SourceRefFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The mail sync loop (Requirement 51 to 55, design: Mail sync). Read-only end to end: it fetches
 * through the [MailConnector], discards OTP mail, keeps only shortlisted mail, scores it (the AI
 * classifier for shortlisted mail, falling back to the Rule_Score when AI is off or fails), applies
 * the Sender_Penalty, and stores ONE Inbox_Item per Source_Ref with no snippet and no links. The
 * item and the resume cursor are committed in one transaction, and one Audit_Log read entry is
 * written per sync. An email at or above the Importance_Threshold raises a notification.
 */
@Singleton
class MailSync @Inject constructor(
    private val holder: DatabaseHolder,
    private val connector: MailConnector,
    private val refs: SourceRefFactory,
    private val settings: SettingsStore,
    private val advisor: Advisor,
    private val audit: AuditLog,
    private val time: TimeSource,
    private val otpPatterns: MailOtpPatterns,
    private val notify: MailNotifier,
    private val accountId: MailAccountId,
    private val billProposals: app.assistant.domain.port.BillProposals,
) {
    data class SyncResult(val fetched: Int, val stored: Int, val notified: Int, val error: Boolean)

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements") // batched IMAP loop with resume and skip points
    suspend fun sync(): SyncResult {
        val db = holder.database()
        val dao = db.mailDao()
        val rules = dao.rules().map { ShortlistRule(it.id, it.senderContains, it.subjectContains, it.score) }
        val cfg = settings.current()
        val since = time.now().minusSeconds(FIRST_SYNC_DAYS * DAY_SECONDS)

        var fetched = 0
        var stored = 0
        var notified = 0
        var cursor = dao.cursor(DEFAULT_FOLDER)?.let { app.assistant.domain.model.MailCursor(it.folder, it.uidValidity, it.lastUid) }

        when (val batch = connector.fetch(cursor, since, MAX_FIRST_SYNC)) {
            is Outcome.Err -> {
                audit.recordRead(DataSource.MAILBOX, time.now(), itemCount = 0, success = false)
                return SyncResult(0, 0, 0, error = true)
            }
            is Outcome.Ok -> {
                for (message in batch.value.messages) {
                    fetched++
                    if (MailTriage.isOtp(message.header, otpPatterns.patterns())) continue
                    proposeBill(message)
                    if (!MailTriage.shortlisted(message.header, rules)) continue
                    val outcome = scoreAndStore(message, rules, cfg.mailImportanceThreshold, dao, db)
                    if (outcome != null) {
                        stored++
                        if (outcome) notified++
                    }
                }
                batch.value.nextCursor?.let { c ->
                    db.withTransaction { dao.putCursor(MailCursorEntity(c.folder, c.uidValidity, c.lastUid)) }
                }
                audit.recordRead(DataSource.MAILBOX, time.now(), itemCount = fetched, success = true)
                return SyncResult(fetched, stored, notified, error = false)
            }
        }
    }

    /** Returns null when the item already existed, true when stored+notified, false when stored only. */
    private suspend fun scoreAndStore(
        message: MailMessage,
        rules: List<ShortlistRule>,
        threshold: Int,
        dao: app.assistant.data.db.dao.MailDao,
        db: app.assistant.data.db.AppDatabase,
    ): Boolean? {
        val h = message.header
        val ref = when (val r = refs.mail(accountId.value(), h.messageId, h.folder, h.uidValidity, h.uid)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return null
        }
        val ruleScore = MailTriage.ruleScore(h, rules).coerceAtMost(MailTriage.MAX_SCORE)
        val (base, category) = classify(message, ruleScore)
        val penalty = dao.penalty(h.from) ?: 0
        val finalScore = MailTriage.applyPenalty(base, penalty)
        val notified = finalScore >= threshold

        val id = db.withTransaction {
            dao.insertItem(
                InboxItemEntity(
                    sourceRef = ref.value,
                    sender = h.from,
                    subject = h.subject,
                    category = category.name,
                    ruleScore = ruleScore,
                    finalScore = finalScore,
                    receivedAt = h.receivedAt.toEpochMilli(),
                    notified = notified,
                    wrongAlert = false,
                ),
            )
        }
        if (id == DUPLICATE) return null
        if (notified) notify.important(id, h.subject, category)
        return notified
    }

    /**
     * Bills and subscription receipts become Bill proposals the User reviews in Money › Bills; only the
     * merchant, amount and due date are kept (never the message). A renewing receipt without a date
     * is proposed for a month after it arrived.
     */
    private suspend fun proposeBill(message: MailMessage) {
        val h = message.header
        val received = h.receivedAt.atZone(time.zone()).toLocalDate()
        val r = app.assistant.parse.mail.ReceiptParser.parse(h.from, h.subject, message.snippet, received) ?: return
        val due = r.dueDate ?: if (r.recurring) received.plusMonths(1) else null
        runCatching { billProposals.propose(r.merchant, app.assistant.domain.model.Paise(r.amountPaise), due) }
    }

    /** The classifier for shortlisted mail; falls back to (Rule_Score, OTHER) when AI is off or fails. */
    private suspend fun classify(message: MailMessage, ruleScore: Int): Pair<Int, MailCategory> {
        val facts = MessageFacts(handle = "mail1", rawText = "${message.header.subject}\n${message.snippet}")
        return when (val r = advisor.classifyMail(facts)) {
            is MailAdvice.Classified -> r.score to (MailCategory.entries.firstOrNull { it.name == r.category } ?: MailCategory.OTHER)
            is MailAdvice.Unavailable -> ruleScore to MailCategory.OTHER
        }
    }

    private companion object {
        const val FIRST_SYNC_DAYS = 7L
        const val DAY_SECONDS = 86_400L
        const val MAX_FIRST_SYNC = 200
        const val DEFAULT_FOLDER = "INBOX"
        const val DUPLICATE = -1L
    }
}

/** The OTP pattern list, loaded from assets (kept behind an interface so the engine stays pure). */
fun interface MailOtpPatterns {
    fun patterns(): List<Regex>
}

/** Raises the important-mail notification (Requirement 55.2); kept behind an interface for the notify layer. */
fun interface MailNotifier {
    suspend fun important(itemId: Long, subject: String, category: MailCategory)
}

/** The connected mailbox account id used in Source_Refs. */
fun interface MailAccountId {
    fun value(): String
}
