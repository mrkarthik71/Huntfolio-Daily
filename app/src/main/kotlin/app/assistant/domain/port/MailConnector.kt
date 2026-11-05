package app.assistant.domain.port

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MailBatch
import app.assistant.domain.model.MailCursor
import app.assistant.domain.model.MailError
import java.time.Instant

/**
 * The read-only mailbox boundary (Requirement 51, design: Mail_Connector). Implementations issue
 * only read commands (IMAP EXAMINE/ENVELOPE/FLAGS/BODY.PEEK or the Gmail read-only API); they never
 * mark, move, delete or send. The connector returns headers + an extracted snippet, never a raw body
 * beyond the snippet window.
 */
interface MailConnector {
    /** Verifies the stored credentials within a 30-second timeout (Requirement 51.5). */
    suspend fun connectionTest(): Outcome<Unit, MailError>

    /**
     * Fetches the next page after [cursor], oldest first, at most [max] messages, going back no
     * further than [since] on a first sync (Requirement 52.4). A null [cursor] starts the window.
     */
    suspend fun fetch(cursor: MailCursor?, since: Instant, max: Int): Outcome<MailBatch, MailError>
}
