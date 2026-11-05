package app.assistant.ui.health

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.port.HealthMonitor
import app.assistant.domain.engine.health.BatteryGuide
import app.assistant.domain.engine.health.HealthRules
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthUi(
    val loading: Boolean = true,
    val problems: Set<HealthRules.Problem> = emptySet(),
    val batterySteps: List<String> = emptyList(),
    val batteryIsStandard: Boolean = true,
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val healthCheck: HealthMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthUi())
    val state: StateFlow<HealthUi> = _state.asStateFlow()

    init { refresh() }

    /** Re-checks on open and on return from the settings action (Requirement 61.6, 62.2). */
    fun refresh() {
        viewModelScope.launch {
            val problems = healthCheck.currentProblems()
            val guide = BatteryGuide.stepsFor(android.os.Build.MANUFACTURER.orEmpty())
            _state.value = HealthUi(loading = false, problems = problems, batterySteps = guide.steps, batteryIsStandard = guide.isStandard)
        }
    }

    fun dismiss(onDone: () -> Unit) {
        viewModelScope.launch {
            healthCheck.dismiss()
            onDone()
        }
    }

    /** Opens the OS battery-optimisation settings; the written steps are the fallback (Requirement 61.4). */
    fun openBatterySettings() {
        runCatching {
            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
