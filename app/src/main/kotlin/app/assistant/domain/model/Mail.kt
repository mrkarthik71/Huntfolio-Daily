package app.assistant.domain.model

import java.time.Instant

/**
 * A single email's headers and the extracted snippet (Requirement 52, design: Mail). Read-only — the
 * app never marks, moves or sends mail. The [snippet] is the plain-text preview after the
 * SnippetExtractor; the raw body never leaves the connector.
 */
data class MailHeader(
    val messageId: String?,
    val folder: String,
    val uidValidity: Long,
    val uid: Long,
    val from: String,
    val subject: String,
    val receivedAt: Instant,
    /** True when the connector could read a body preview; the snippet is then non-null. */
    val hasBody: Boolean,
)

/** One fetched email: its header plus the already-extracted snippet text (never the raw body). */
data class MailMessage(val header: MailHeader, val snippet: String)

/** A page of fetched mail and the cursor to resume after it (Requirement 51.7, 52.4). */
data class MailBatch(val messages: List<MailMessage>, val nextCursor: MailCursor?)

/**
 * Where mail sync resumes: the folder, its UIDVALIDITY and the last UID seen. A changed UIDVALIDITY
 * means the folder was renumbered and sync restarts from the window (Requirement 52.4).
 */
data class MailCursor(val folder: String, val uidValidity: Long, val lastUid: Long)

/** Why a mail operation failed. The connector maps every transport problem to one of these. */
enum class MailError {
    /** The credentials were rejected: sync stops and the mailbox needs reconnection (Requirement 51.6). */
    AUTH_REJECTED,

    /** The connection test or a fetch timed out (30 seconds, Requirement 51.5). */
    TIMEOUT,

    /** A transport or protocol error (no reply, unexpected response). */
    TRANSPORT,

    /** The folder named by the cursor no longer exists. */
    FOLDER_GONE,
}
