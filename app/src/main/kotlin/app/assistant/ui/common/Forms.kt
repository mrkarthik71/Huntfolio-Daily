package app.assistant.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.error.ValidationError
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Maps a validation error to its message (Requirements 11.2, 12.2 and the other form rules). */
fun errorText(e: ValidationError): Int = when (e) {
    ValidationError.EMPTY -> R.string.error_empty
    ValidationError.TOO_LONG -> R.string.error_too_long
    ValidationError.OUT_OF_RANGE -> R.string.error_out_of_range
    ValidationError.NOT_A_NUMBER -> R.string.error_not_a_number
    ValidationError.INVALID_DATE -> R.string.error_invalid_date
    ValidationError.IN_PAST -> R.string.error_in_past
    ValidationError.IN_FUTURE -> R.string.error_in_future
    ValidationError.DUPLICATE -> R.string.error_duplicate
    ValidationError.LIMIT_REACHED -> R.string.error_limit_reached
    ValidationError.NOT_ALLOWED -> R.string.error_not_allowed
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun formatDate(d: LocalDate): String = d.format(dateFormat)

fun formatTime(t: LocalTime): String = t.format(timeFormat)

/** A button that shows a date and opens a date picker; the date can be cleared when [clearable]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    error: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
                Text("$label: ${value?.let(::formatDate) ?: stringResource(R.string.task_not_set)}")
            }
            if (clearable && value != null) {
                TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.clear)) }
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        // The picker works in UTC milliseconds; a calendar date has no time zone.
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** A button that shows a clock time and opens a time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, value: LocalTime, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("$label: ${formatTime(value)}") }
    if (open) {
        val state = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.time_pick_title)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalTime.of(state.hour, state.minute))
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
