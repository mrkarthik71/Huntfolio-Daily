package app.assistant.ui.life

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.KeepInTouchRules
import app.assistant.domain.engine.life.ListRules
import app.assistant.domain.engine.life.NoteSearch
import app.assistant.domain.model.JournalEntry
import app.assistant.domain.model.KeepInTouch
import app.assistant.domain.model.ListItem
import app.assistant.domain.model.Note
import app.assistant.domain.port.PersonalStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.PillButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** One view model for the four small personal screens: notes, journal, lists and keep in touch. */
@HiltViewModel
@Suppress("TooManyFunctions") // thin pass-throughs to the store for four screens
class PersonalViewModel @Inject constructor(private val store: PersonalStore, private val time: TimeSource) : ViewModel() {
    val today: LocalDate get() = time.today()
    val notes: StateFlow<List<Note>> = store.notes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val journal: StateFlow<List<JournalEntry>> = store.journal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val items: StateFlow<List<ListItem>> = store.listItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val people: StateFlow<List<KeepInTouch>> = store.people().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun saveNote(id: Long, title: String, body: String, pinned: Boolean) {
        if (title.isBlank() && body.isBlank()) return
        viewModelScope.launch { store.saveNote(id, title, body, pinned, time.now()) }
    }

    fun deleteNote(id: Long) = launch { store.deleteNote(id) }

    fun saveJournal(date: LocalDate, text: String) = launch { store.saveJournal(date, text) }

    fun addItem(list: String, title: String, repeatDays: Int?) {
        if (list.isBlank() || title.isBlank()) return
        launch { store.addListItem(list, title, repeatDays) }
    }

    fun toggleItem(item: ListItem) = launch { store.setListItemDone(item.id, ListRules.isOpen(item, time.today()), time.today()) }

    fun deleteItem(id: Long) = launch { store.deleteListItem(id) }

    fun savePerson(id: Long, name: String, everyDays: Int) {
        if (name.isBlank()) return
        launch { store.savePerson(id, name, everyDays) }
    }

    fun talked(id: Long) = launch { store.talkedTo(id, time.today()) }

    fun deletePerson(id: Long) = launch { store.deletePerson(id) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** The shared frame of these screens: back key, caption, title and an add key. */
@Composable
private fun PersonalFrame(caption: String, title: String, onBack: () -> Unit, onAdd: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(caption, Modifier.padding(start = 14.dp).weight(1f), color = MaterialTheme.colorScheme.primary)
                if (onAdd != null) KeyButton(Icons.Filled.Add, stringResource(R.string.life_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.enterAnimated(0))
        }
        content()
    }
}

@Composable
private fun Empty(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------- Notes

@Composable
fun NotesScreen(onBack: () -> Unit, viewModel: PersonalViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Note?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val shown = notes.filter { NoteSearch.matches(it, query) }
    PersonalFrame(stringResource(R.string.notes_caption), stringResource(R.string.notes_title), onBack, onAdd = { adding = true }) {
        item { LifeField(query, { query = it }, stringResource(R.string.notes_search)) }
        if (shown.isEmpty()) item { Empty(stringResource(if (notes.isEmpty()) R.string.notes_empty else R.string.notes_no_match)) }
        items(shown, key = { it.id }) { n ->
            Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.medium).clickable { editing = n }.padding(14.dp)) {
                if (n.pinned) Caption(stringResource(R.string.notes_pinned), color = MaterialTheme.colorScheme.primary)
                if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                if (n.body.isNotBlank()) {
                    Text(
                        n.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (adding || editing != null) {
        NoteSheet(editing, onDismiss = {
            adding = false
            editing = null
        }, viewModel)
    }
}

@Composable
private fun NoteSheet(note: Note?, onDismiss: () -> Unit, viewModel: PersonalViewModel) {
    var title by rememberSaveable { mutableStateOf(note?.title.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(note?.body.orEmpty()) }
    var pinned by rememberSaveable { mutableStateOf(note?.pinned ?: false) }
    LifeSheet(stringResource(if (note == null) R.string.notes_new else R.string.notes_edit), onDismiss) {
        LifeField(title, { title = it }, stringResource(R.string.notes_field_title))
        OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.notes_field_body)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
        OptionKey(stringResource(R.string.notes_pin), pinned, { pinned = !pinned })
        CredPrimaryButton(stringResource(R.string.life_save), onClick = {
            viewModel.saveNote(note?.id ?: 0, title, body, pinned)
            onDismiss()
        }, enabled = title.isNotBlank() || body.isNotBlank())
        if (note != null) {
            TextButton(onClick = {
                viewModel.deleteNote(note.id)
                onDismiss()
            }) { Text(stringResource(R.string.life_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------------------------------------------------------------- Journal

@Composable
fun JournalScreen(onBack: () -> Unit, viewModel: PersonalViewModel = hiltViewModel()) {
    val entries by viewModel.journal.collectAsStateWithLifecycle()
    val today = viewModel.today
    val todays = entries.firstOrNull { it.date == today }
    var text by rememberSaveable(todays?.text) { mutableStateOf(todays?.text.orEmpty()) }
    PersonalFrame(stringResource(R.string.journal_caption), stringResource(R.string.journal_title), onBack, onAdd = null) {
        item {
            Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Caption(formatDate(today), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.journal_prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth())
                CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                    viewModel.saveJournal(today, text)
                }, enabled = text != todays?.text.orEmpty())
            }
        }
        val past = entries.filter { it.date != today }
        if (past.isNotEmpty()) item { Caption(stringResource(R.string.journal_past)) }
        items(past, key = { it.date.toString() }) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Caption(formatDate(e.date))
                Text(e.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// ---------------------------------------------------------------- Lists

private val REPEAT_CHOICES = listOf(null, 3, 7, 14, 30)

@Composable
fun ListsScreen(onBack: () -> Unit, onOpenReminders: () -> Unit, viewModel: PersonalViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val today = viewModel.today
    val groups = ListRules.grouped(items, today)
    PersonalFrame(stringResource(R.string.lists_caption), stringResource(R.string.lists_title), onBack, onAdd = { adding = true }) {
        if (groups.isEmpty()) item { Empty(stringResource(R.string.lists_empty)) }
        groups.forEach { (name, list) ->
            item(key = "h-$name") {
                Caption(stringResource(R.string.lists_count, name.uppercase(), list.count { ListRules.isOpen(it, today) }), Modifier.padding(top = 8.dp))
            }
            items(list, key = { it.id }) { i ->
                val open = ListRules.isOpen(i, today)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = !open, onCheckedChange = { viewModel.toggleItem(i) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            i.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (open) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        i.repeatDays?.let {
                            Text(
                                stringResource(R.string.lists_every, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.deleteItem(i.id) }) { Text(stringResource(R.string.life_delete)) }
                }
            }
        }
        item {
            PillButton(
                stringResource(R.string.lists_place),
                onClick = onOpenReminders,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (adding) {
        var list by rememberSaveable { mutableStateOf(groups.firstOrNull()?.first ?: "") }
        var title by rememberSaveable { mutableStateOf("") }
        var repeat by rememberSaveable { mutableStateOf<Int?>(null) }
        val defaultList = stringResource(R.string.lists_default)
        LifeSheet(stringResource(R.string.lists_add), { adding = false }) {
            LifeField(title, { title = it }, stringResource(R.string.lists_item))
            LifeField(list, { list = it }, stringResource(R.string.lists_list_name, defaultList))
            if (groups.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { (n, _) -> OptionKey(n, list == n, { list = n }) }
                }
            }
            Caption(stringResource(R.string.lists_repeat))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REPEAT_CHOICES.forEach { r ->
                    OptionKey(if (r == null) stringResource(R.string.lists_once) else stringResource(R.string.lists_every, r), repeat == r, { repeat = r })
                }
            }
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                viewModel.addItem(list.ifBlank { defaultList }, title, repeat)
                title = ""
            }, enabled = title.isNotBlank())
        }
    }
}

// ---------------------------------------------------------------- Keep in touch

private val EVERY_CHOICES = listOf(7, 14, 30, 90)

@Composable
fun PeopleScreen(onBack: () -> Unit, viewModel: PersonalViewModel = hiltViewModel()) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<KeepInTouch?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val today = viewModel.today
    PersonalFrame(stringResource(R.string.people_caption), stringResource(R.string.people_title), onBack, onAdd = { adding = true }) {
        item { TouchNotify() }
        if (people.isEmpty()) item { Empty(stringResource(R.string.people_empty)) }
        items(KeepInTouchRules.ordered(people, today), key = { it.id }) { p ->
            val due = KeepInTouchRules.isDue(p, today)
            val since = KeepInTouchRules.daysSince(p, today)
            Row(
                Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.medium).clickable { editing = p }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (since == null) stringResource(R.string.people_never) else stringResource(R.string.people_since, since.toInt(), p.everyDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (due) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.talked(p.id) }) { Text(stringResource(R.string.people_talked)) }
            }
        }
    }
    if (adding || editing != null) {
        val p = editing
        var name by rememberSaveable { mutableStateOf(p?.name.orEmpty()) }
        var every by rememberSaveable { mutableStateOf(p?.everyDays ?: EVERY_CHOICES[1]) }
        val close = {
            adding = false
            editing = null
        }
        LifeSheet(stringResource(R.string.people_add), close) {
            LifeField(name, { name = it }, stringResource(R.string.people_name))
            Caption(stringResource(R.string.people_every_caption))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EVERY_CHOICES.forEach { d -> OptionKey(stringResource(R.string.lists_every, d), every == d, { every = d }) }
            }
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                viewModel.savePerson(p?.id ?: 0, name, every)
                close()
            }, enabled = name.isNotBlank())
            if (p != null) {
                TextButton(onClick = {
                    viewModel.deletePerson(p.id)
                    close()
                }) { Text(stringResource(R.string.life_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
