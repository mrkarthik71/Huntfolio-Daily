package app.assistant.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.ai.gateway.CapGovernor
import app.assistant.ai.ondevice.OnDeviceAiProvider
import app.assistant.domain.engine.settings.SettingsField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.port.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the AI settings section shows (Requirement 48.7, 45.4, 45.6). */
data class AiSettingsUi(
    val loading: Boolean = true,
    val mode: AiMode = AiMode.NONE,
    val monthlyCapPaise: Long = 0,
    val spentThisMonthPaise: Long = 0,
    /** True when this month's spend has reached the cap; [resumeDate] then tells the user when it resets. */
    val capReached: Boolean = false,
    val resumeDate: String = "",
    /** Personal flavour only: whether a Cloud key is stored (never the key itself). */
    val keyConfigured: Boolean = false,
    /** Human reason the on-device model is unavailable, or null when it is usable. */
    val onDeviceUnavailable: String? = null,
    val capError: Boolean = false,
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val cap: CapGovernor,
    private val onDevice: OnDeviceAiProvider,
    private val keyWriter: app.assistant.ai.cloud.CloudKeyWriter,
) : ViewModel() {

    private val spent = MutableStateFlow(0L)
    private val capError = MutableStateFlow(false)
    private val keyConfigured = MutableStateFlow(false)

    /** True in the personal flavour (key entry shown), false in shared. */
    val keyEntrySupported: Boolean = keyWriter.supported

    init {
        viewModelScope.launch {
            spent.value = cap.spent()
            keyConfigured.value = keyWriter.hasKey()
        }
    }

    val state: StateFlow<AiSettingsUi> = combine(settings.settings, spent, capError, keyConfigured) { s, spentPaise, err, hasKey ->
        AiSettingsUi(
            loading = false,
            mode = s.aiMode,
            monthlyCapPaise = s.aiMonthlyCapPaise,
            spentThisMonthPaise = spentPaise,
            capReached = s.aiMonthlyCapPaise > 0 && spentPaise >= s.aiMonthlyCapPaise,
            resumeDate = cap.resumeDateIso(),
            keyConfigured = hasKey,
            onDeviceUnavailable = onDevice.lastUnavailable?.name,
            capError = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUi())

    /** Stores (or, when blank, clears) the Cloud AI key. Personal flavour only. */
    fun setKey(key: String) {
        viewModelScope.launch {
            keyWriter.setKey(key)
            keyConfigured.value = keyWriter.hasKey()
        }
    }

    fun setMode(mode: AiMode) {
        viewModelScope.launch { settings.update { it.copy(aiMode = mode) } }
    }

    /** Sets the monthly cap in rupees; an invalid value keeps the previous one (Requirement 45.6). */
    fun setCapRupees(rupees: String) {
        val paise = rupees.trim().toDoubleOrNull()?.let { (it * PAISE_PER_RUPEE).toLong() }
        if (paise == null) {
            capError.value = true
            return
        }
        viewModelScope.launch {
            when (settings.update { it.copy(aiMonthlyCapPaise = paise) }) {
                is Outcome.Ok -> capError.value = false
                is Outcome.Err -> capError.value = true
            }
        }
    }

    fun setModel(type: app.assistant.domain.model.PromptType, model: String) {
        viewModelScope.launch {
            settings.update { s -> s.copy(aiModels = if (model.isBlank()) s.aiModels - type else s.aiModels + (type to model.trim())) }
        }
    }

    private companion object {
        const val PAISE_PER_RUPEE = 100
    }
}
