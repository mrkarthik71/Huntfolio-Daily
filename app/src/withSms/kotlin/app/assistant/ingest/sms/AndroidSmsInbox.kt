package app.assistant.ingest.sms

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import app.assistant.domain.port.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the phone's SMS inbox through the system ContentResolver (withSms build only). It asks for the address, body and
 * two timestamps and NOTHING else, and returns only rows from senders that pass [isListed] — an SMS from anyone else never
 * leaves this adapter, so no part of it is stored, logged or parsed (Requirement 26.3). It reads from [Telephony.Sms.Inbox]
 * only, and never writes. A missing permission makes the query throw, which [SmsReaderCore] turns into an audited failed read.
 */
@Singleton
class AndroidSmsInbox @Inject constructor(@ApplicationContext private val context: Context, private val dispatchers: DispatcherProvider) : SmsInbox {

    override suspend fun since(start: Instant, isListed: (String) -> Boolean): List<InboxSms> = withContext(dispatchers.io) {
        val startMillis = start.toEpochMilli()
        // DATE is the Received_Time (ms). We bound the query by it and sort ascending so the cursor advances forward.
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(startMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return@withContext emptyList()

        cursor.use { readListed(it, isListed) }
    }

    private fun readListed(cursor: Cursor, isListed: (String) -> Boolean): List<InboxSms> {
        val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val dateSentIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
        val rows = ArrayList<InboxSms>(cursor.count)
        while (cursor.moveToNext()) {
            val address = cursor.getString(addressIdx)
            // The sender test runs here, before the body leaves the adapter, so an unlisted sender's text is never held.
            if (address == null || !isListed(address)) continue
            rows += InboxSms(
                address = address,
                body = cursor.getString(bodyIdx),
                dateSent = instantOrNull(cursor.getLong(dateSentIdx)),
                dateReceived = Instant.ofEpochMilli(cursor.getLong(dateIdx)),
            )
        }
        return rows
    }

    private fun instantOrNull(millis: Long): Instant? = if (millis > 0) Instant.ofEpochMilli(millis) else null
}
