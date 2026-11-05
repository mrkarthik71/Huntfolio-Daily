package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only Audit_Log row (Requirement 7). `kind` is READ, AI_SEND or AI_RESULT.
 * AI_RESULT rows link to their AI_SEND row through [parentId]; no row is ever updated.
 * [payload] holds the exact Redacted_Payload for AI_SEND rows and is null otherwise.
 */
@Entity(tableName = "audit_entry", indices = [Index("at"), Index("parent_id")])
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val source: String?,
    @ColumnInfo(name = "item_count") val itemCount: Int?,
    val success: Boolean?,
    @ColumnInfo(name = "ai_mode") val aiMode: String?,
    @ColumnInfo(name = "prompt_type") val promptType: String?,
    val payload: String?,
    @ColumnInfo(name = "parent_id") val parentId: Long?,
)

/** A secret encrypted with the Keystore secret key (mail credentials, Cloud AI key). Never exported. */
@Entity(tableName = "secret")
data class SecretEntity(@PrimaryKey val name: String, val blob: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SecretEntity && other.name == name && other.blob.contentEquals(blob)

    override fun hashCode(): Int = 31 * name.hashCode() + blob.contentHashCode()
}

/** Diagnostics ring buffer row: a code and enum/number details only (SafeLog). */
@Entity(tableName = "diagnostic_event", indices = [Index("at")])
data class DiagnosticEventEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val code: String, val detail: String)
