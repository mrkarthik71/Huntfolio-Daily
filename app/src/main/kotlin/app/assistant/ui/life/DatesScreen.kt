package app.assistant.ui.life

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.DateRules
import app.assistant.domain.model.DateKind
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.ContactDatesReader
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.designsystem.recessedWell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DateRow(val date: ImportantDate, val next: LocalDate, val daysLeft: Long, val years: Int?)

@HiltViewModel
class DatesViewModel @Inject constructor(
    private val store: DateStore,
    private val contacts: ContactDatesReader,
    private val time: TimeSource,
    notify: NotifySwitch,
) : LifeViewModel(notify, NotificationType.IMPORTANT_DATE) {
    val today: LocalDate = time.today()

    val rows: StateFlow<List<DateRow>> = store.dates().map { list ->
        list.mapNotNull { d ->
            val next = DateRules.nextOccurrence(d, today) ?: return@mapNotNull null
            DateRow(d, next, java.time.temporal.ChronoUnit.DAYS.between(today, next), DateRules.yearsAt(d, today))
        }.sortedBy { it.next }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    private val _imported = MutableStateFlow<Int?>(null)
    val imported: StateFlow<Int?> = _imported.asStateFlow()

    fun save(title: String, kind: DateKind, date: LocalDate, existing: ImportantDate?) {
        if (title.isBlank()) return
        replanAfter {
            store.save(ImportantDate(existing?.id ?: 0, title, kind, date, yearly = kind == DateKind.BIRTHDAY || kind == DateKind.ANNIVERSARY))
            if (existing == null && !notifyOn.value) setNotify(true)
        }
    }

    fun delete(id: Long) = replanAfter { store.delete(id) }

    /** Called after the contacts permission was granted. */
    fun importContacts() {
        viewModelScope.launch {
            _imported.value = store.importContacts(contacts.read())
            if (!notifyOn.value) setNotify(true)
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun kindLabel(kind: DateKind): String = stringResource(
    when (kind) {
        DateKind.BIRTHDAY -> R.string.dates_kind_birthday
        DateKind.ANNIVERSARY -> R.string.dates_kind_anniversary
        DateKind.DOCUMENT -> R.string.dates_kind_document
        DateKind.INSURANCE -> R.string.dates_kind_insurance
        DateKind.WARRANTY -> R.string.dates_kind_warranty
        DateKind.OTHER -> R.string.dates_kind_other
    },
)

@Composable
fun DatesScreen(viewModel: DatesViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ImportantDate?>(null) }
    var askContacts by rememberSaveable { mutableStateOf(false) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) viewModel.importContacts() }
    DatesContent(rows, notify, imported, viewModel::setNotify, onAdd = { adding = true }, onEdit = { editing = it }, onImport = { askContacts = true })
    if (askContacts) {
        // The plain-language reason comes before the system dialog (Requirement 5.2).
        AlertDialog(
            onDismissRequest = { askContacts = false },
            title = { Text(stringResource(R.string.dates_contacts_title)) },
            text = { Text(stringResource(R.string.dates_contacts_reason)) },
            confirmButton = {
                TextButton(onClick = {
                    askContacts = false
                    contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                }) { Text(stringResource(R.string.life_continue)) }
            },
            dismissButton = { TextButton(onClick = { askContacts = false }) { Text(stringResource(R.string.life_cancel)) } },
        )
    }
    if (adding || editing != null) {
        DateSheet(editing, viewModel.today, onDismiss = {
            adding = false
            editing = null
        }, onSave = { t, k, d ->
            viewModel.save(t, k, d, editing)
            adding = false
            editing = null
        }, onDelete = editing?.let { e ->
            {
                viewModel.delete(e.id)
                editing = null
            }
        })
    }
}

@Composable
@Suppress("LongParameterList") // state plus one callback per action
fun DatesContent(
    rows: List<DateRow>,
    notify: Boolean,
    imported: Int?,
    onNotify: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ImportantDate) -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().enterAnimated(0), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.dates_caption))
                    Text(stringResource(R.string.dates_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                }
                KeyButton(Icons.Filled.Add, stringResource(R.string.dates_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item { NotifyToggle(notify, stringResource(R.string.dates_notify), stringResource(R.string.dates_notify_reason), onNotify, Modifier.enterAnimated(1)) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onImport) { Text(stringResource(R.string.dates_import), color = MaterialTheme.colorScheme.primary) }
                if (imported != null) Text(stringResource(R.string.dates_imported, imported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (rows.isEmpty()) item { Text(stringResource(R.string.dates_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        itemsIndexed(rows, key = { _, r -> r.date.id }) { i, r -> DateCard(r, Modifier.enterAnimated(i + 2).clickable { onEdit(r.date) }) }
    }
}

@Composable
private fun DateCard(r: DateRow, modifier: Modifier = Modifier) {
    val soon = r.daysLeft <= SOON_DAYS
    Row(
        modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.widthIn(min = 64.dp).recessedWell(MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (r.daysLeft == 0L) stringResource(R.string.dates_today_short) else "${r.daysLeft}",
                style = app.assistant.ui.designsystem.AssistantType.Readout.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = if (soon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            )
            if (r.daysLeft > 0) Caption(stringResource(R.string.dates_days_caption))
        }
        Column(Modifier.weight(1f)) {
            Caption(kindLabel(r.date.kind))
            Text(r.date.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                formatDate(r.next) + (r.years?.let { " · " + stringResource(R.string.dates_years, it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateSheet(existing: ImportantDate?, today: LocalDate, onDismiss: () -> Unit, onSave: (String, DateKind, LocalDate) -> Unit, onDelete: (() -> Unit)?) {
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: DateKind.BIRTHDAY) }
    var date by remember { mutableStateOf(existing?.date ?: today) }
    var picking by remember { mutableStateOf(false) }
    LifeSheet(stringResource(if (existing == null) R.string.dates_add else R.string.dates_edit), onDismiss) {
        LifeField(title, { title = it }, stringResource(R.string.dates_name))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateKind.entries.forEach { k -> OptionKey(kindLabel(k), kind == k, { kind = k }) }
        }
        Caption(stringResource(if (kind == DateKind.BIRTHDAY || kind == DateKind.ANNIVERSARY) R.string.dates_on else R.string.dates_expires_on))
        OptionKey(formatDate(date), true, { picking = true })
        CredPrimaryButton(stringResource(R.string.life_save), onClick = { onSave(title, kind, date) }, enabled = title.isNotBlank())
        if (onDelete != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.life_delete), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (picking) {
        DatePickDialog(date, onPick = {
            date = it
            picking = false
        }, onDismiss = { picking = false })
    }
}

private const val SOON_DAYS = 7L
