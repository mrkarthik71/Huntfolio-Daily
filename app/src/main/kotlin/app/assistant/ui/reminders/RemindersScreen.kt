package app.assistant.ui.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.model.DeviceTrigger
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.model.SmartReminder
import app.assistant.ui.common.DateField
import app.assistant.ui.common.TimeField
import app.assistant.ui.common.formatDate
import app.assistant.ui.common.formatTime
import app.assistant.ui.designsystem.ApexCard
import app.assistant.ui.designsystem.EmptyState
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.Eyebrow
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.enterAnimated
import java.time.LocalDate
import java.time.LocalTime

/**
 * Smart reminders (Module C): at a time, at a place, or when the phone does something. Location and
 * Bluetooth are asked for only when the User adds a place or car reminder, after a plain rationale,
 * one permission group per request (product rules).
 */
@Composable
fun RemindersScreen(onBack: () -> Unit, viewModel: RemindersViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf<ReminderKind?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ui.quietStart != null && ui.quietEnd != null) {
                            stringResource(
                                R.string.reminders_summary_quiet,
                                ui.reminders.count {
                                    it.active
                                },
                                formatTime(ui.quietStart!!),
                                formatTime(ui.quietEnd!!),
                            )
                        } else {
                            stringResource(R.string.reminders_summary, ui.reminders.count { it.active })
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                }
            }
        }
        item {
            ApexCard(Modifier.enterAnimated(0)) {
                Eyebrow(stringResource(R.string.reminders_when))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindTile(stringResource(R.string.reminders_kind_time), Icons.Outlined.DateRange, Modifier.weight(1f)) { adding = ReminderKind.TIME }
                    KindTile(stringResource(R.string.reminders_kind_place), Icons.Outlined.LocationOn, Modifier.weight(1f)) { adding = ReminderKind.PLACE }
                    KindTile(stringResource(R.string.reminders_kind_device), Icons.Outlined.Notifications, Modifier.weight(1f)) { adding = ReminderKind.DEVICE }
                }
            }
        }
        if (!ui.loading && ui.reminders.isEmpty()) {
            item { EmptyState(stringResource(R.string.reminders_empty)) }
        }
        ReminderKind.entries.forEach { kind ->
            val list = ui.reminders.filter { it.kind == kind }
            if (list.isNotEmpty()) {
                item { Eyebrow(stringResource(sectionTitle(kind)), Modifier.padding(start = 4.dp, top = 4.dp)) }
                item {
                    ApexCard {
                        list.forEach { r ->
                            ReminderRow(
                                r,
                                subtitle = subtitle(r, viewModel.zone()),
                                onToggle = { viewModel.setActive(r.id, it) },
                                onDelete = { viewModel.delete(r.id) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.reminders_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    adding?.let { kind ->
        AddReminderDialog(
            kind = kind,
            today = viewModel.today(),
            onSave = {
                viewModel.save(it)
                adding = null
            },
            onDismiss = { adding = null },
            zone = viewModel.zone(),
        )
    }
}

@Composable
private fun KindTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 76.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ReminderRow(r: SmartReminder, subtitle: String, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val tint = when (r.kind) {
        ReminderKind.TIME -> EnergyColors.DeepWork
        ReminderKind.PLACE -> EnergyColors.Physical
        ReminderKind.DEVICE -> EnergyColors.LightAdmin
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(
                when (r.kind) {
                    ReminderKind.TIME -> Icons.Outlined.DateRange
                    ReminderKind.PLACE -> Icons.Outlined.LocationOn
                    ReminderKind.DEVICE -> Icons.Outlined.Notifications
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(r.message, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.reminders_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Switch(checked = r.active, onCheckedChange = onToggle)
    }
}

private fun sectionTitle(kind: ReminderKind): Int = when (kind) {
    ReminderKind.TIME -> R.string.reminders_section_time
    ReminderKind.PLACE -> R.string.reminders_section_place
    ReminderKind.DEVICE -> R.string.reminders_section_device
}

@Composable
private fun subtitle(r: SmartReminder, zone: java.time.ZoneId): String = when (r.kind) {
    ReminderKind.TIME -> r.fireAt?.atZone(zone)?.let {
        stringResource(R.string.reminders_at_time, formatDate(it.toLocalDate()), formatTime(it.toLocalTime()))
    }.orEmpty()
    ReminderKind.PLACE -> stringResource(
        if (r.onEnter) R.string.reminders_arriving else R.string.reminders_leaving,
        r.placeName.orEmpty(),
        r.radiusMeters ?: 0,
    )
    ReminderKind.DEVICE -> {
        val what = stringResource(if (r.device == DeviceTrigger.CAR_BLUETOOTH) R.string.reminders_device_car else R.string.reminders_device_charging)
        r.notBefore?.let { stringResource(R.string.reminders_device_after, what, formatTime(it)) } ?: what
    }
}

/** Adds one reminder; asks for location or Bluetooth only here, with the reason shown first. */
@SuppressLint("MissingPermission")
@Composable
@Suppress("CyclomaticComplexMethod") // one form, three reminder kinds; branches are per field
private fun AddReminderDialog(kind: ReminderKind, today: LocalDate, zone: java.time.ZoneId, onSave: (SmartReminder) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<LocalDate?>(today) }
    var timeOfDay by rememberSaveable { mutableStateOf(LocalTime.of(9, 0)) }
    var placeName by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lng by rememberSaveable { mutableStateOf<Double?>(null) }
    var radius by rememberSaveable { mutableStateOf(RADII[1]) }
    var onEnter by rememberSaveable { mutableStateOf(true) }
    var device by rememberSaveable { mutableStateOf(DeviceTrigger.CHARGING) }
    var useNotBefore by rememberSaveable { mutableStateOf(false) }
    var notBefore by rememberSaveable { mutableStateOf(LocalTime.of(18, 0)) }
    var note by rememberSaveable { mutableStateOf<Int?>(null) }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun readLocation() {
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = runCatching {
            lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        if (loc == null) {
            note = R.string.reminders_location_unknown
        } else {
            lat = loc.latitude
            lng = loc.longitude
            note = R.string.reminders_location_set
        }
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) note = R.string.reminders_background_denied
    }
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            readLocation()
            // Background location is its own request, after foreground, as Android requires.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            note = R.string.reminders_location_denied
        }
    }
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) note = R.string.reminders_bluetooth_denied
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminders_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(MAX_MESSAGE) },
                    label = { Text(stringResource(R.string.reminders_message)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (kind) {
                    ReminderKind.TIME -> {
                        DateField(label = stringResource(R.string.reminders_date), value = date, onChange = { date = it }, clearable = false)
                        TimeField(label = stringResource(R.string.reminders_time), value = timeOfDay, onChange = { timeOfDay = it })
                    }
                    ReminderKind.PLACE -> {
                        OutlinedTextField(
                            value = placeName,
                            onValueChange = { placeName = it.take(MAX_PLACE) },
                            label = { Text(stringResource(R.string.reminders_place_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.reminders_location_rationale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PillButton(
                            stringResource(if (lat == null) R.string.reminders_use_here else R.string.reminders_location_set),
                            onClick = {
                                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) readLocation() else fine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            },
                            container = MaterialTheme.colorScheme.surface,
                            content = MaterialTheme.colorScheme.onSurface,
                            border = MaterialTheme.colorScheme.outline,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = onEnter, onClick = { onEnter = true }, label = { Text(stringResource(R.string.reminders_on_arrive)) })
                            FilterChip(selected = !onEnter, onClick = { onEnter = false }, label = { Text(stringResource(R.string.reminders_on_leave)) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RADII.forEach { m ->
                                FilterChip(selected = radius == m, onClick = { radius = m }, label = { Text(stringResource(R.string.reminders_radius, m)) })
                            }
                        }
                    }
                    ReminderKind.DEVICE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = device == DeviceTrigger.CHARGING, onClick = {
                                device = DeviceTrigger.CHARGING
                            }, label = { Text(stringResource(R.string.reminders_device_charging)) })
                            FilterChip(
                                selected = device == DeviceTrigger.CAR_BLUETOOTH,
                                onClick = {
                                    device = DeviceTrigger.CAR_BLUETOOTH
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                                        note = R.string.reminders_bluetooth_rationale
                                    }
                                },
                                label = { Text(stringResource(R.string.reminders_device_car)) },
                            )
                        }
                        if (device == DeviceTrigger.CAR_BLUETOOTH &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            !granted(Manifest.permission.BLUETOOTH_CONNECT)
                        ) {
                            PillButton(
                                stringResource(R.string.reminders_allow_bluetooth),
                                onClick = { bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                                container = MaterialTheme.colorScheme.surface,
                                content = MaterialTheme.colorScheme.onSurface,
                                border = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = useNotBefore, onCheckedChange = { useNotBefore = it })
                            Text(stringResource(R.string.reminders_not_before), Modifier.padding(start = 8.dp))
                        }
                        if (useNotBefore) TimeField(label = stringResource(R.string.reminders_time), value = notBefore, onChange = { notBefore = it })
                    }
                }
                note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            val valid = message.isNotBlank() &&
                when (kind) {
                    ReminderKind.TIME -> date != null
                    ReminderKind.PLACE -> lat != null && lng != null && placeName.isNotBlank()
                    ReminderKind.DEVICE -> true
                }
            TextButton(enabled = valid, onClick = {
                onSave(
                    SmartReminder(
                        id = ReminderId(0),
                        message = message.trim(),
                        kind = kind,
                        fireAt = if (kind == ReminderKind.TIME) date?.atTime(timeOfDay)?.atZone(zone)?.toInstant() else null,
                        placeName = placeName.trim().takeIf { kind == ReminderKind.PLACE },
                        latitude = lat.takeIf { kind == ReminderKind.PLACE },
                        longitude = lng.takeIf { kind == ReminderKind.PLACE },
                        radiusMeters = radius.takeIf { kind == ReminderKind.PLACE },
                        onEnter = onEnter,
                        device = device.takeIf { kind == ReminderKind.DEVICE },
                        notBefore = notBefore.takeIf { kind == ReminderKind.DEVICE && useNotBefore },
                    ),
                )
            }) { Text(stringResource(R.string.subs_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val RADII = listOf(100, 200, 500)
private const val MAX_MESSAGE = 120
private const val MAX_PLACE = 60
