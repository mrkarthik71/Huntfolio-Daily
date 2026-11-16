package app.assistant.ui.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import app.assistant.ui.MainActivity

/**
 * The "Ask Apex" quick-settings tile: one tap opens Ask listening by voice. It opens the app through
 * MainActivity, so the app lock still applies. It reads nothing and stores nothing.
 */
class AskTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(ROUTE, VOICE_ROUTE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") // the Intent form is the only one before Android 14
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val ROUTE = "route"
        const val VOICE_ROUTE = "ask?voice=true"
    }
}
