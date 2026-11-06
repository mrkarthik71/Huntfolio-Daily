package app.assistant.ui.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.assistant.ui.MainActivity

/**
 * Receives text shared from another app ("Share › Apex") and hands it to the main app, so it goes
 * through the app lock and the encrypted store like everything else. The text is the User's own
 * choice to share; it is treated as data, never as instructions, and links in it are never opened.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(MAX_SHARED) else null
        if (!text.isNullOrEmpty()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_ROUTE, "share?text=" + android.net.Uri.encode(text)),
            )
        }
        finish()
    }

    private companion object {
        const val MAX_SHARED = 500

        // The key MainActivity reads its start route from.
        const val EXTRA_ROUTE = "route"
    }
}
