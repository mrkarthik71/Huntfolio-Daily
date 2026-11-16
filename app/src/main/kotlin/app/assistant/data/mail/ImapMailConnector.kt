package app.assistant.data.mail

import app.assistant.domain.engine.mail.SnippetExtractor
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MailBatch
import app.assistant.domain.model.MailCursor
import app.assistant.domain.model.MailError
import app.assistant.domain.model.MailHeader
import app.assistant.domain.model.MailMessage
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.MailConnector
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.time.Instant
import java.util.Properties

/** The IMAP connection details for the mailbox (host, port, user); the password comes separately. */
data class ImapConfig(val host: String, val port: Int, val username: String, val useSsl: Boolean = true)

/** Supplies the IMAP config + password at call time; the password is held only for the call. */
interface MailCredentialStore {
    suspend fun config(): ImapConfig?
    suspend fun password(): CharArray?
}

/**
 * Read-only IMAP Mail_Connector over Jakarta/Angus Mail (Requirement 51, task 34.1). It opens the
 * folder with [Folder.READ_ONLY] (IMAP EXAMINE), reads ENVELOPE/FLAGS and a peeked body window
 * (BODY.PEEK leaves \Seen untouched), and never issues a write command. The connection test has a
 * 30-second timeout; rejected credentials map to [MailError.AUTH_REJECTED] so sync stops and the
 * mailbox is marked needs-reconnection. Resume uses UIDVALIDITY + last UID; a changed UIDVALIDITY
 * restarts the window.
 */
class ImapMailConnector(private val credentials: MailCredentialStore, private val dispatchers: DispatcherProvider, private val snippetLength: () -> Int) :
    MailConnector {

    override suspend fun connectionTest(): Outcome<Unit, MailError> = withContext(dispatchers.io) {
        withStore { store ->
            val folder = store.getFolder(DEFAULT_FOLDER)
            folder.open(Folder.READ_ONLY)
            folder.close(false)
            Outcome.Ok(Unit)
        }
    }

    override suspend fun fetch(cursor: MailCursor?, since: Instant, max: Int): Outcome<MailBatch, MailError> = withContext(dispatchers.io) {
        withStore { store ->
            val folder = store.getFolder(DEFAULT_FOLDER) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val uidValidity = folder.uidValidity
                // A changed UIDVALIDITY means the folder was renumbered: restart the window from UID 1.
                val startUid = if (cursor != null && cursor.uidValidity == uidValidity) cursor.lastUid + 1 else 1L
                val messages = folder.getMessagesByUID(startUid, IMAPFolder.LASTUID)
                    .filterNotNull()
                    .take(max)
                    .mapNotNull { msg -> toMessage(folder, msg, since) }
                val lastUid = messages.maxOfOrNull { it.header.uid } ?: cursor?.lastUid ?: 0L
                val next = if (messages.isEmpty()) cursor else MailCursor(DEFAULT_FOLDER, uidValidity, lastUid)
                Outcome.Ok(MailBatch(messages, next))
            } finally {
                folder.close(false)
            }
        }
    }

    private fun toMessage(folder: IMAPFolder, msg: jakarta.mail.Message, since: Instant): MailMessage? {
        val received = msg.receivedDate?.toInstant() ?: msg.sentDate?.toInstant() ?: return null
        if (received.isBefore(since)) return null
        val uid = folder.getUID(msg)
        val header = MailHeader(
            messageId = msg.getHeader("Message-ID")?.firstOrNull(),
            folder = folder.fullName,
            uidValidity = folder.uidValidity,
            uid = uid,
            from = msg.from?.firstOrNull()?.toString().orEmpty(),
            subject = msg.subject.orEmpty(),
            receivedAt = received,
            hasBody = true,
        )
        return MailMessage(header, snippet = snippet(msg))
    }

    /** Builds the snippet from the message body, preferring a text/plain part. BODY.PEEK keeps \Seen off. */
    private fun snippet(msg: jakarta.mail.Message): String {
        val parts = mutableListOf<SnippetExtractor.Part>()
        collectParts(msg.content, msg.contentType, parts)
        return SnippetExtractor.extract(parts, snippetLength())
    }

    private fun collectParts(content: Any?, contentType: String, into: MutableList<SnippetExtractor.Part>) {
        when (content) {
            is String ->
                into +=
                    SnippetExtractor.Part(
                        content.toByteArray(charset(contentType)),
                        charsetOf(contentType),
                        isHtml = contentType.contains("html", ignoreCase = true),
                    )
            is MimeMultipart -> for (i in 0 until content.count) {
                val part = content.getBodyPart(i)
                runCatching { collectParts(part.content, part.contentType, into) }
            }
        }
    }

    private fun charset(contentType: String): java.nio.charset.Charset =
        runCatching { charsetOf(contentType)?.let(java.nio.charset.Charset::forName) }.getOrNull() ?: Charsets.UTF_8

    private fun charsetOf(contentType: String): String? = Regex("""charset=\"?([\w-]+)""", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)

    private inline fun <T> withStore(block: (IMAPStore) -> Outcome<T, MailError>): Outcome<T, MailError> {
        val config = runCatchingBlocking { credentials.config() } ?: return Outcome.Err(MailError.AUTH_REJECTED)
        val password = runCatchingBlocking { credentials.password() } ?: return Outcome.Err(MailError.AUTH_REJECTED)
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.host)
            put("mail.imaps.port", config.port.toString())
            put("mail.imaps.ssl.enable", config.useSsl.toString())
            put("mail.imaps.connectiontimeout", TIMEOUT_MS.toString())
            put("mail.imaps.timeout", TIMEOUT_MS.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps") as IMAPStore
        return try {
            store.connect(config.host, config.username, String(password))
            block(store)
        } catch (_: jakarta.mail.AuthenticationFailedException) {
            Outcome.Err(MailError.AUTH_REJECTED)
        } catch (_: Exception) {
            Outcome.Err(MailError.TRANSPORT)
        } finally {
            password.fill('\u0000')
            runCatching { if (store.isConnected) store.close() }
        }
    }

    // credentials.config()/password() are suspend; the connector already runs on the IO dispatcher,
    // so a blocking bridge here is safe and keeps withStore non-suspend for the finally/try shape.
    private fun <T> runCatchingBlocking(block: suspend () -> T): T? = runCatching { kotlinx.coroutines.runBlocking { block() } }.getOrNull()

    private companion object {
        const val DEFAULT_FOLDER = "INBOX"
        const val TIMEOUT_MS = 30_000
    }
}
