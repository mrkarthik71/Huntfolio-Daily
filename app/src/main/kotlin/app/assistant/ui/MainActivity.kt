package app.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.assistant.security.lock.AppLock
import app.assistant.ui.app.AppViewModel
import app.assistant.ui.app.AssistantApp
import app.assistant.ui.designsystem.AssistantTheme
import app.assistant.ui.permission.AndroidPermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single activity. FLAG_SECURE keeps user data out of screenshots and the recent-apps
 * preview (Requirement 6.5). A FragmentActivity because BiometricPrompt needs one.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock

    @Inject lateinit var permissions: AndroidPermissionManager

    private val appViewModel: AppViewModel by viewModels()

    private val startRoute = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // FLAG_SECURE keeps user data out of screenshots and the recent-apps preview (Requirement
        // 6.5). It is dropped in DEBUG builds only, so the UI can be screenshotted during
        // development; release builds are always secure.
        if (!app.assistant.BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        permissions.attach(this)
        startRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            AssistantTheme {
                AssistantApp(
                    viewModel = appViewModel,
                    startRoute = startRoute.value,
                    onUnlock = { lifecycleScope.launch { appLock.authenticate(this@MainActivity) } },
                    onOpenSecuritySettings = {
                        runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        appLock.retryAfterSettings()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A notification tapped while the app is running routes after the next unlock.
        intent.getStringExtra(EXTRA_ROUTE)?.let { startRoute.value = it }
    }

    private companion object {
        const val EXTRA_ROUTE = "route"
    }

    override fun onStart() {
        super.onStart()
        // Revoked permissions turn their features off (Requirement 5.7).
        permissions.checkRevocations()
    }
}
