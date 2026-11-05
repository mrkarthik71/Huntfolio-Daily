package app.assistant.ingest.sms

import java.time.Instant

/**
 * One SMS as the phone's inbox shows it: the sender address, the whole body (the provider has already joined the parts)
 * and the two times it records. [dateSent] is the carrier's timestamp and may be missing or zero on some phones.
 * [toString] hides the content (Requirement 3.5).
 */
class InboxSms(val address: String?, val body: String?, val dateSent: Instant?, val dateReceived: Instant) {
    override fun toString(): String = "InboxSms"
}

/**
 * Reads the phone's SMS inbox (the Android side lives in the withSms build). It is given a test for sender IDs and must
 * return only messages from senders that pass it, so an SMS from anyone else never leaves the adapter (Requirement 26.3).
 */
interface SmsInbox {
    /** Messages received at or after [start] from senders for which [isListed] is true. May throw if the permission is gone. */
    suspend fun since(start: Instant, isListed: (String) -> Boolean): List<InboxSms>
}

/** Where the Sms_Reader got to: the Received_Time of the last SMS it processed (Requirement 26.6). It never moves backwards. */
interface SmsCursor {
    suspend fun lastReceivedAt(): Instant?

    suspend fun advanceTo(at: Instant)
}
