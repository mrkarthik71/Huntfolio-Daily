package app.assistant.ingest.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot, enqueues the SMS catch-up so SMS that arrived while the process was stopped are read (Requirement 26.6).
 * withSms build only, disabled behind @bool/phase_money so it ships off until the money phase is on. It touches no
 * message content; the worker checks the enabled switch and reads only listed senders. The main SystemEventReceiver still
 * handles rescheduling of notifications on boot separately.
 */
class SmsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> SmsCatchupWorker.enqueue(context.applicationContext)
            else -> Unit
        }
    }
}
