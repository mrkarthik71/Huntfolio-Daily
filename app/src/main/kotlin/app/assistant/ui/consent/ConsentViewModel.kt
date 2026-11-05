package app.assistant.ui.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.data.consent.ConsentStore
import app.assistant.domain.engine.consent.ConsentGate
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Consent_Screen for the shared build (Requirement 66). It reads whether consent must be
 * shown and records acceptance; a decline simply never records, so the gate shows again next launch.
 */
@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val store: ConsentStore,
    private val time: TimeSource,
) : ViewModel() {

    private val _accepted = MutableStateFlow(false)
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

    fun accept() {
        viewModelScope.launch {
            store.accept(ConsentGate.CURRENT_VERSION, time.now().toEpochMilli())
            _accepted.value = true
        }
    }
}
