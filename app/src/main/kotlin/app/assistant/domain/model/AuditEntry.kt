package app.assistant.domain.model

import java.time.Instant

enum class AuditKind { READ, AI_SEND, AI_RESULT }

/** One Audit_Log row as shown on the Audit_Log screen (Requirement 7.4). */
data class AuditEntry(
    val id: AuditId,
    val at: Instant,
    val kind: AuditKind,
    val source: DataSource?,
    val itemCount: Int?,
    val success: Boolean?,
    val aiMode: AiMode?,
    val promptType: PromptType?,
    /** The exact Redacted_Payload, for AI_SEND rows only. */
    val payload: String?,
    val parentId: AuditId?,
)
