package app.assistant.domain.port

import app.assistant.domain.career.HuntfolioError
import app.assistant.domain.career.HuntfolioImport
import app.assistant.domain.engine.health.HealthRules
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MailError
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

// Ports the UI uses instead of reaching into the data and ai layers (design: Dependency rules).

/** Reads a picked Huntfolio export, enforcing the 5 MB limit before parsing (Requirement 59.1). */
fun interface CareerFileReader {
    fun read(stream: InputStream, sizeBytes: Long): Outcome<HuntfolioImport, HuntfolioError>
}

/** The accepted consent version for the shared build (Requirement 66). */
interface ConsentRecord {
    fun acceptedVersion(): Int?

    fun accept(version: Int, atEpochMs: Long)
}

/** The app-health check behind the Health screen and the health warning (Requirement 65). */
interface HealthMonitor {
    suspend fun currentProblems(): Set<HealthRules.Problem>

    suspend fun shouldWarn(): Boolean

    suspend fun dismiss()
}

/** What the AI settings screen shows about spend and the on-device model (Requirement 44, 46). */
interface AiUsage {
    /** Cloud spend this month, in paise. */
    suspend fun spentThisMonthPaise(): Long

    /** ISO date the monthly cap resets. */
    fun resumeDateIso(): String

    /** Why the on-device model is unavailable, or null when it is ready. */
    fun onDeviceUnavailableReason(): String?
}

/** A Shortlist_Rule (Requirement 53). */
data class MailRule(val id: Long, val senderContains: String, val subjectContains: String, val score: Int)

/** An Inbox_Item: headers and scores only, never a snippet or link (Requirement 54.7). */
data class MailInboxItem(val id: Long, val sender: String, val subject: String, val category: String, val finalScore: Int)

/** Mail alert quality (Requirement 55.4, 58). */
data class MailQualitySummary(val wrongAlerts8w: Int, val notifications8w: Int, val avgNotificationsPerDay: Double)

/**
 * The read-only mailbox feature behind the Mail screen (Requirements 51 to 57): connect with an IMAP
 * app password (stored only after a successful test), sync scheduling, rules, and the inbox.
 */
interface MailboxService {
    fun rules(): Flow<List<MailRule>>

    fun inbox(): Flow<List<MailInboxItem>>

    suspend fun isConnected(): Boolean

    suspend fun quality(): MailQualitySummary

    /** Tests the login; credentials are kept and sync is scheduled only on success. */
    suspend fun connect(host: String, port: Int, username: String, password: String): Outcome<Unit, MailError>

    suspend fun disconnect()

    /** Re-schedules the sync worker after the interval changed (no-op while disconnected). */
    suspend fun rescheduleSync(intervalMinutes: Int)

    suspend fun addRule(senderContains: String, subjectContains: String, score: Int)

    suspend fun deleteRule(id: Long)

    /** Raises the sender's penalty and counts one Wrong_Mail_Alert (Requirement 55.3, 55.5). */
    suspend fun markNotImportant(itemId: Long, sender: String)
}

/** Arms the phone triggers behind smart reminders (time jobs, place alerts, charging/car signals). */
interface ReminderScheduler {
    suspend fun sync()

    fun disarm(id: app.assistant.domain.model.ReminderId)
}
