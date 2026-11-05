package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.SettingsStore
import app.assistant.ui.designsystem.AssistantColors
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.designsystem.recessedWell
import app.assistant.ui.permission.Feature
import app.assistant.ui.permission.PermissionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * Turns one notification type on or off for a feature screen. Turning it on asks for notification
 * permission first (Requirement 5.1); the screen shows the plain-language reason next to the switch.
 */
class NotifySwitch @Inject constructor(
    private val settings: SettingsStore,
    private val permissions: PermissionManager,
    private val scheduler: NotificationScheduling,
) {
    fun isOn(type: NotificationType): Flow<Boolean> = settings.settings.map { type in it.enabledTypes }

    suspend fun set(type: NotificationType, on: Boolean) {
        if (on) permissions.request(Feature.NOTIFICATIONS)
        settings.update { it.copy(enabledTypes = if (on) it.enabledTypes + type else it.enabledTypes - type) }
        scheduler.refresh()
    }

    /** Re-plans alarms after an item changed. */
    suspend fun replan() = scheduler.refresh()
}

/** Base for the feature view models: exposes whether this screen's notifications are on. */
abstract class LifeViewModel(private val notify: NotifySwitch, private val type: NotificationType) : ViewModel() {
    val notifyOn: StateFlow<Boolean> = notify.isOn(type).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    fun setNotify(on: Boolean) {
        viewModelScope.launch { notify.set(type, on) }
    }

    protected fun replanAfter(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            notify.replan()
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** The "remind me" switch with its reason, on a raised panel. */
@Composable
fun NotifyToggle(on: Boolean, title: String, reason: String, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.medium, elevation = 8.dp)
            .toggleable(on, role = Role.Switch, onValueChange = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = AssistantColors.Well,
            ),
        )
    }
}

/** A bottom sheet for adding or editing an item, on the raised panel colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AssistantColors.PanelTop) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            content()
        }
    }
}

@Composable
fun LifeField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Seven day keys; selected days rise out of the well. */
@Composable
fun DayToggles(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(Modifier.fillMaxWidth().recessedWell(MaterialTheme.shapes.medium).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { d ->
            val on = d in selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .let { if (on) it.raisedPanel(MaterialTheme.shapes.small, elevation = 4.dp) else it }
                    .toggleable(on, role = Role.Checkbox) { onToggle(d) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A small chip-style key for picking an option. */
@Composable
fun OptionKey(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 44.dp)
            .let { if (selected) it.raisedPanel(CircleShape, elevation = 4.dp) else it.recessedWell(CircleShape) }
            .toggleable(selected, role = Role.RadioButton) { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AssistantColors.PanelTop,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.life_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.life_cancel)) } },
        text = { TimePicker(state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text(stringResource(R.string.life_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.life_cancel)) } },
    ) { DatePicker(state) }
}

/** A caption above a group on the ground. */
@Composable
fun GroupCaption(text: String) = Caption(text, Modifier.padding(start = 4.dp, top = 8.dp))
