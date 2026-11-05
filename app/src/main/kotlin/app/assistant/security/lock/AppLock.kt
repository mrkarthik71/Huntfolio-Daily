package app.assistant.security.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.assistant.R
import app.assistant.domain.model.AppSettings
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * App_Lock: a UI gate in front of all user data (ADR 0003). It is registered as a process
 * lifecycle observer, so moving the whole app to the background starts the Lock_Timeout.
 */
@Singleton
class AppLock @Inject constructor(@param:ApplicationContext private val context: Context, settings: SettingsStore, @ApplicationScope scope: CoroutineScope) :
    DefaultLifecycleObserver {

    private val machine = LockStateMachine()
    private val _state = MutableStateFlow(machine.state)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val lockTimeout = settings.settings.map { it.lockTimeout }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings().lockTimeout)

    override fun onStart(owner: LifecycleOwner) = update { machine.onForeground(SystemClock.elapsedRealtime(), lockTimeout.value) }

    override fun onStop(owner: LifecycleOwner) = update { machine.onBackground(SystemClock.elapsedRealtime()) }

    /** Shows the system prompt: biometric or the device PIN, pattern or password. */
    suspend fun authenticate(activity: FragmentActivity): AuthResult {
        // DEBUG builds skip the system BiometricPrompt: emulators have no reliable biometric and
        // the prompt can render blank, blocking the UI. Release builds always run the real prompt.
        if (app.assistant.BuildConfig.DEBUG) {
            update { machine.onAuthStarted() }
            update { machine.onAuthResult(AuthResult.SUCCESS) }
            return AuthResult.SUCCESS
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            update { machine.onAuthResult(AuthResult.NO_CREDENTIAL) }
            return AuthResult.NO_CREDENTIAL
        }
        update { machine.onAuthStarted() }
        val result = prompt(activity)
        update { machine.onAuthResult(result) }
        return result
    }

    /** Called when the User returns from the screen-lock settings page. */
    fun retryAfterSettings() = update { machine.reset() }

    /** Locks at once, for example after "Delete everything". */
    fun lockNow() = update { machine.reset() }

    private suspend fun prompt(activity: FragmentActivity): AuthResult = suspendCancellableCoroutine { cont ->
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(AuthResult.SUCCESS)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val mapped = when (errorCode) {
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthResult.NO_CREDENTIAL
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> AuthResult.CANCELLED
                    else -> AuthResult.FAILED
                }
                if (cont.isActive) cont.resume(mapped)
            }

            // A single failed fingerprint keeps the prompt open; only errors end it.
            override fun onAuthenticationFailed() = Unit
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(authenticators())
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private inline fun update(block: () -> Unit) {
        block()
        _state.value = machine.state
    }

    companion object {
        /**
         * androidx.biometric does not support BIOMETRIC_STRONG together with DEVICE_CREDENTIAL on
         * API 28 and 29, so those versions (and 26 to 27, where the library falls back to the
         * KeyguardManager credential screen) use BIOMETRIC_WEAK with DEVICE_CREDENTIAL.
         */
        fun authenticators(sdk: Int = Build.VERSION.SDK_INT): Int =
            if (sdk >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
