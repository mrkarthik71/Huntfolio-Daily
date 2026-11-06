package app.assistant.ingest.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.ReaderStateStore
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * The SMS_RECEIVED receiver (Requirement 26.1, 26.7), in the withSms build only and declared disabled behind
 * @bool/phase_money, so it ships off until the money phase is on. On delivery it joins the parts of one SMS in order
 * (26.7), checks reading is enabled, and hands the message to [SmsReaderCore]; the core drops a message from an unlisted
 * sender before the pipeline, with no stored trace (26.3). It uses goAsync so the ContentResolver/pipeline work finishes
 * off the main thread. It never reads an SMS body here — only the PDU parts Android already delivered — and stores nothing
 * itself; all storage goes through the pipeline inside [SmsReaderCore].
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var core: SmsReaderCore

    @Inject lateinit var readerState: ReaderStateStore

    @Inject lateinit var time: TimeSource

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // All parts of one SMS share the originating address and the SMS timestamp; join their bodies in order (26.7).
        val address = messages.first().originatingAddress
        val parts = messages.mapNotNull { it.messageBody }
        val sentAtMillis = messages.first().timestampMillis
        val receivedAt = time.now()

        val message = SmsAssembler.fromParts(
            address = address,
            parts = parts,
            sentAt = if (sentAtMillis > 0) Instant.ofEpochMilli(sentAtMillis) else null,
            receivedAt = receivedAt,
        ) ?: return

        val pending = goAsync()
        scope.launch {
            try {
                // A race between a reboot catch-up and live delivery is safe: ingest is idempotent by Source_Ref (29.3).
                if (readerState.current().smsEnabled) {
                    runCatching { core.onLive(message) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
