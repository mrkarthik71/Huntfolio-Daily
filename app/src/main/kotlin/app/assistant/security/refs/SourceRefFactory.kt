package app.assistant.security.refs

import app.assistant.domain.error.Outcome
import app.assistant.domain.error.map
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.SourceRef
import app.assistant.security.keys.KeyError
import app.assistant.security.keys.KeyManager
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds Source_Refs: `<prefix>:v1:<hmac>`, where `hmac` is the first 128 bits, base32-encoded, of
 * HMAC-SHA256 under the Source_Ref key (ADR 0006, design: Source_Ref scheme). A ref holds no message
 * text and cannot be reversed by dictionary attack without the key. It is the idempotency key for
 * ingest (Requirement 29.3) and the key for tombstones.
 *
 * The HMAC input is built from the stable identifiers of each source, never stored, and framed with
 * a length before every field so that `("ab", "c")` and `("a", "bc")` can never give the same input.
 * The same ref must come out of live reading and of history import for the same message, so each
 * input is normalised the same way on both paths. Changing any of this changes every ref and breaks
 * idempotence across a restore, which is why the tests pin exact values.
 */
class SourceRefFactory(private val keys: KeyManager) {

    /**
     * An SMS: normalised sender ID, PDU timestamp (`date_sent`) and SHA-256 of the joined body. The
     * timestamp is cut to whole seconds, the resolution of the PDU, so a broadcast and the inbox
     * provider agree even if one reports milliseconds.
     */
    fun sms(senderId: String, sentAt: Instant, joinedBody: String): Outcome<SourceRef, KeyError> = make(
        DataSource.SMS,
        utf8(senderId.trim().uppercase(Locale.ROOT)),
        utf8(sentAt.epochSecond.toString()),
        sha256(utf8(joinedBody)),
    )

    /** An app notification: package name, notification `key`, SHA-256 of title and text. An updated text is a new message. */
    fun notification(packageName: String, key: String, title: String, text: String): Outcome<SourceRef, KeyError> = make(
        DataSource.APP_NOTIFICATIONS,
        utf8(packageName),
        utf8(key),
        sha256(frame(listOf(utf8(title), utf8(text)))),
    )

    /** One ref per occurrence of a calendar event (Requirement 22.6). */
    fun calendar(calendarId: Long, eventId: Long, occurrenceBegin: Instant): Outcome<SourceRef, KeyError> = make(
        DataSource.CALENDAR,
        utf8(calendarId.toString()),
        utf8(eventId.toString()),
        utf8(occurrenceBegin.toEpochMilli().toString()),
    )

    /** An email: account and Message-ID header, or folder, UIDVALIDITY and UID when the header is absent. */
    fun mail(accountId: String, messageId: String?, folder: String, uidValidity: Long, uid: Long): Outcome<SourceRef, KeyError> =
        if (!messageId.isNullOrBlank()) {
            make(DataSource.MAILBOX, utf8("message-id"), utf8(accountId), utf8(messageId.trim()))
        } else {
            make(DataSource.MAILBOX, utf8("uid"), utf8(accountId), utf8(folder), utf8(uidValidity.toString()), utf8(uid.toString()))
        }

    /** A Huntfolio record, so a re-import is idempotent (Requirement 59.5). */
    fun huntfolio(recordKey: String): Outcome<SourceRef, KeyError> = make(DataSource.HUNTFOLIO, utf8(recordKey))

    private fun make(source: DataSource, vararg fields: ByteArray): Outcome<SourceRef, KeyError> = keys.sourceRefKey().map { key ->
        try {
            val input = frame(listOf(utf8(source.prefix), utf8(VERSION)) + fields.toList())
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(input)
            SourceRef("${source.prefix}:$VERSION:${Base32.encode(mac.copyOf(HMAC_BYTES))}")
        } finally {
            // The manager hands out a copy; do not leave the key sitting in memory longer than needed.
            key.fill(0)
        }
    }

    private companion object {
        const val VERSION = "v1"
        const val HMAC = "HmacSHA256"

        /** 128 bits. */
        const val HMAC_BYTES = 16

        fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        /** Each field preceded by its 4-byte big-endian length. */
        fun frame(fields: List<ByteArray>): ByteArray {
            val buffer = ByteBuffer.allocate(fields.sumOf { Int.SIZE_BYTES + it.size })
            fields.forEach { buffer.putInt(it.size).put(it) }
            return buffer.array()
        }
    }
}
