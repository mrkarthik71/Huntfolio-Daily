package app.assistant.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.port.DataWiper
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.EncryptedStoreGate
import app.assistant.domain.port.StoreStatus
import app.assistant.domain.port.UnlockFailure
import app.assistant.security.lock.AppLock
import app.assistant.security.lock.LockState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the root of the app shows. User data screens appear only in [Ready] while unlocked. */
sealed interface AppUiState {
    data object Starting : AppUiState

    /** Shared build only: the Consent_Screen must be accepted before the store is created (Req 66.1). */
    data object NeedsConsent : AppUiState

    data class CannotUnlock(val reason: UnlockFailure) : AppUiState

    data class Ready(val lock: LockState) : AppUiState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val store: EncryptedStoreGate,
    private val wiper: DataWiper,
    private val dispatchers: DispatcherProvider,
    private val consent: app.assistant.data.consent.ConsentStore,
    val appLock: AppLock,
) : ViewModel() {

    private val starting = MutableStateFlow(true)
    private val needsConsent = MutableStateFlow(false)

    val state: StateFlow<AppUiState> = combine(starting, needsConsent, store.status, appLock.state) { isStarting, consentNeeded, status, lock ->
        when {
            consentNeeded -> AppUiState.NeedsConsent
            isStarting -> AppUiState.Starting
            status is StoreStatus.Unavailable -> AppUiState.CannotUnlock(status.reason)
            status is StoreStatus.Open -> AppUiState.Ready(lock)
            else -> AppUiState.Starting
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Starting)

    init {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                // Shared build: show consent BEFORE touching the store, key or settings (Requirement 66.1).
                if (app.assistant.BuildConfig.SHARED_BUILD &&
                    app.assistant.domain.engine.consent.ConsentGate.mustShow(true, consent.acceptedVersion())
                ) {
                    needsConsent.value = true
                    return@withContext
                }
                // An interrupted wipe finishes before anything else is shown (Requirement 8.7).
                wiper.resumeIfPending()
                store.open()
            }
            starting.value = false
        }
    }

    /** Called when the Consent_Screen is accepted: now the store may be created. */
    fun onConsentAccepted() {
        needsConsent.value = false
        viewModelScope.launch {
            withContext(dispatchers.io) {
                wiper.resumeIfPending()
                store.open()
            }
            starting.value = false
        }
    }

    /** "Delete everything", after the User confirmed the dialog (Requirement 8.5). */
    fun deleteEverything() {
        viewModelScope.launch {
            starting.value = true
            withContext(dispatchers.io) {
                wiper.wipeAll()
                store.open()
            }
            starting.value = false
        }
    }
}
