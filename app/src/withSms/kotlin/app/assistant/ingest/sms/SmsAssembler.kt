package app.assistant.ingest.sms

import app.assistant.ingest.SmsIngestRules
import app.assistant.ingest.pipeline.RawMessage
import java.time.Instant

/**
 * Turns what the phone hands over into the message the pipeline reads. Live delivery and the inbox must give the same
 * message, and so the same Source_Ref, for the same SMS, or a message read live and again by catch-up would be counted
 * twice: both go through [sentAtOrReceived] and the same trimming.
 */
object SmsAssembler {
    /**
     * An SMS that arrived in one or more parts, in order, as the SMS_RECEIVED broadcast delivers it (Requirement 26.7).
     * Null when there is no sender or nothing in the body, since there is nothing to read.
     */
    fun fromParts(address: String?, parts: List<String>, sentAt: Instant?, receivedAt: Instant): RawMessage.Sms? {
        val sender = address?.trim().orEmpty()
        if (sender.isEmpty() || parts.isEmpty()) return null
        val body = SmsIngestRules.joinParts(parts.mapIndexed { index, part -> index to part })
        return build(sender, body, sentAt, receivedAt)
    }

    /** A row of the SMS inbox, used by catch-up and the history import. Null when it has no sender or no body. */
    fun fromInbox(row: InboxSms): RawMessage.Sms? {
        val sender = row.address?.trim().orEmpty()
        val body = row.body.orEmpty()
        return if (sender.isEmpty()) null else build(sender, body, row.dateSent, row.dateReceived)
    }

    private fun build(sender: String, body: String, sentAt: Instant?, receivedAt: Instant): RawMessage.Sms? =
        if (body.isBlank()) null else RawMessage.Sms(sender, body, sentAtOrReceived(sentAt, receivedAt), receivedAt)

    /** Some phones report a sent time of zero; the Received_Time stands in for it, the same way on every path. */
    internal fun sentAtOrReceived(sentAt: Instant?, receivedAt: Instant): Instant = sentAt?.takeIf { it.toEpochMilli() > 0 } ?: receivedAt
}
