package app.assistant.domain.port

import app.assistant.domain.engine.settings.SettingsField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.AuditEntry
import app.assistant.domain.model.AuditId
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.PromptType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** State of the Encrypted_Store as seen by the rest of the app. */
sealed interface StoreStatus {
    data object NotOpened : StoreStatus

    data object Open : StoreStatus

    /** The stored data cannot be unlocked; the database file is left untouched (Requirement 1.5). */
    data class Unavailable(val reason: UnlockFailure) : StoreStatus
}

enum class UnlockFailure { KEYSTORE_UNAVAILABLE, KEY_INVALIDATED, KEY_FILE_DAMAGED, DATABASE_ERROR }

interface EncryptedStoreGate {
    val status: StateFlow<StoreStatus>

    /** Opens the store if needed; safe to call repeatedly. Blocking: call from an IO dispatcher. */
    fun open(): StoreStatus
}

interface DataWiper {
    /** Writes the wipe marker first, then deletes everything (Requirement 8.5). */
    suspend fun wipeAll()

    /** Called at launch before any screen; finishes an interrupted wipe (Requirement 8.7). */
    suspend fun resumeIfPending(): Boolean
}

interface SourceDisconnect {
    /** Stops reads, deletes credentials and, only if asked, the source's facts (Requirement 8.2, 8.3). */
    suspend fun disconnect(source: DataSource, deleteFacts: Boolean)
}

/** Connected or not, per Data_Source (Requirement 8.1). Each phase contributes its sources. */
interface SourceStatusProvider {
    val source: DataSource
    val connected: Flow<Boolean>
}

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    /** Stores the change only if every field is valid; otherwise keeps the previous values. */
    suspend fun update(change: (AppSettings) -> AppSettings): Outcome<AppSettings, FieldErrors<SettingsField>>
}

enum class AuditError { WRITE_FAILED }

/** Audit_Log (Requirement 7). Append-only: rows are inserted and read, never updated. */
interface AuditLog {
    /** Every finished read, including reads with zero items and failed reads (Requirement 7.1). */
    suspend fun recordRead(source: DataSource, at: Instant, itemCount: Int, success: Boolean)

    /** Must complete before the payload is sent; Err means do not send (Requirement 7.3). */
    suspend fun recordAiSend(at: Instant, mode: AiMode, type: PromptType, payload: String): Outcome<AuditId, AuditError>

    /** Inserts a separate result row linked to the send row. */
    suspend fun markAiResult(id: AuditId, at: Instant, success: Boolean)

    /** Newest first (Requirement 7.4). */
    fun newest(limit: Int): Flow<List<AuditEntry>>
}
