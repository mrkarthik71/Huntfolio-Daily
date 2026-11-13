package app.assistant.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.DateRules
import app.assistant.domain.engine.search.SearchHit
import app.assistant.domain.engine.search.SearchKind
import app.assistant.domain.engine.search.SearchRules
import app.assistant.domain.engine.search.Searchable
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.MemoryRepository
import app.assistant.domain.port.PersonalStore
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.AssistantColors
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.consoleGround
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Search everything Apex keeps, on the phone. The index is built once when the screen opens (from
 * the encrypted store) and lives only in memory while the screen is open.
 */
@HiltViewModel
@Suppress("LongParameterList") // one read port per kind of thing that can be searched
class SearchViewModel @Inject constructor(
    private val personal: PersonalStore,
    private val tasks: TaskRepository,
    private val transactions: TransactionRepository,
    private val bills: BillRepository,
    private val memory: MemoryRepository,
    private val dates: DateStore,
    private val time: TimeSource,
) : ViewModel() {
    private var index: List<Searchable> = emptyList()
    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits: StateFlow<List<SearchHit>> = _hits.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private var last = ""

    init {
        viewModelScope.launch {
            index = build()
            _ready.value = true
            if (last.isNotBlank()) query(last)
        }
    }

    fun query(q: String) {
        last = q
        _hits.value = SearchRules.search(index, q)
    }

    @Suppress("LongMethod") // one block per source, each read on its own so one failing source does not hide the rest
    private suspend fun build(): List<Searchable> {
        val zone = time.zone()
        val today = time.today()
        val out = ArrayList<Searchable>()
        runCatching {
            personal.notes().first().forEach {
                out +=
                    Searchable(SearchKind.NOTE, it.title.ifBlank { it.body.take(TITLE_CHARS) }, it.body, it.updatedAt.atZone(zone).toLocalDate())
            }
        }
        runCatching { personal.journal().first().forEach { out += Searchable(SearchKind.JOURNAL, formatDate(it.date), it.text, it.date) } }
        runCatching { personal.listItems().first().forEach { out += Searchable(SearchKind.LIST, it.title, it.list) } }
        runCatching { personal.people().first().forEach { out += Searchable(SearchKind.PERSON, it.name, "", it.lastTalked) } }
        runCatching {
            personal.trips().first().forEach { t ->
                out += Searchable(
                    SearchKind.TRIP,
                    t.title,
                    listOfNotNull(t.ref, t.from, t.to, formatDate(t.startsAt.atZone(zone).toLocalDate())).joinToString(" "),
                    t.startsAt.atZone(zone).toLocalDate(),
                )
            }
        }
        runCatching {
            personal.orders().first().forEach { o ->
                out +=
                    Searchable(
                        SearchKind.ORDER,
                        o.merchant,
                        listOfNotNull(o.orderRef, o.status.name.lowercase()).joinToString(" "),
                        o.updatedAt.atZone(zone).toLocalDate(),
                    )
            }
        }
        runCatching { tasks.openTasks().first().forEach { out += Searchable(SearchKind.TASK, it.title, "", it.plannedDate ?: it.dueDate) } }
        runCatching {
            val from = today.minusYears(1)
            transactions.transactions().first().forEach { t ->
                val day = t.at.atZone(zone).toLocalDate()
                val name = t.merchantName ?: return@forEach
                if (!day.isBefore(from)) out += Searchable(SearchKind.PAYMENT, name, "${MoneyFormatter.format(t.amount)} ${formatDate(day)}", day)
            }
        }
        runCatching {
            bills.bills().first().forEach {
                out +=
                    Searchable(SearchKind.BILL, it.name, "${MoneyFormatter.format(it.amount)} ${formatDate(it.dueDate)}", it.dueDate)
            }
        }
        runCatching {
            memory.notes().first().forEach {
                out +=
                    Searchable(SearchKind.MEMORY, it.text.take(TITLE_CHARS), it.text, it.createdAt.atZone(zone).toLocalDate())
            }
        }
        runCatching {
            dates.datesNow().forEach { d -> out += Searchable(SearchKind.DATE, d.title, d.kind.name.lowercase(), DateRules.nextOccurrence(d, today)) }
        }
        return out
    }

    private companion object {
        const val TITLE_CHARS = 60
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.search_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            OutlinedTextField(
                q,
                {
                    q = it
                    viewModel.query(it)
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).focusRequester(focus),
            )
        }
        when {
            !ready -> item { Caption(stringResource(R.string.search_indexing), live = true) }
            q.trim().length < SearchRules.MIN_QUERY -> item {
                Text(stringResource(R.string.search_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            hits.isEmpty() -> item {
                Text(
                    stringResource(R.string.search_none, q.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                item { Caption(stringResource(R.string.search_count, hits.size)) }
                items(hits, key = { System.identityHashCode(it) }) { hit -> HitRow(hit, onClick = { onOpen(routeOf(hit.item.kind)) }) }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(kindLabel(hit.item.kind)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(end = 10.dp).size(width = 64.dp, height = 16.dp),
            maxLines = 1,
        )
        Box(Modifier.padding(end = 12.dp).size(width = 4.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)).background(markerOf(hit.item.kind)))
        Column(Modifier.weight(1f)) {
            Text(
                hit.item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(hit.item.body.takeIf { it.isNotBlank() }, hit.item.date?.let(::formatDate)).distinct().joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun markerOf(kind: SearchKind): Color = when (kind) {
    SearchKind.PAYMENT, SearchKind.BILL, SearchKind.ORDER -> AssistantColors.Info
    SearchKind.TASK, SearchKind.LIST -> AssistantColors.Signal
    SearchKind.PERSON, SearchKind.DATE -> AssistantColors.Good
    else -> AssistantColors.Neutral
}

private fun kindLabel(kind: SearchKind): Int = when (kind) {
    SearchKind.NOTE -> R.string.search_k_note
    SearchKind.TASK -> R.string.search_k_task
    SearchKind.LIST -> R.string.search_k_list
    SearchKind.JOURNAL -> R.string.search_k_journal
    SearchKind.PERSON -> R.string.search_k_person
    SearchKind.TRIP -> R.string.search_k_trip
    SearchKind.ORDER -> R.string.search_k_order
    SearchKind.PAYMENT -> R.string.search_k_payment
    SearchKind.BILL -> R.string.search_k_bill
    SearchKind.MEMORY -> R.string.search_k_memory
    SearchKind.DATE -> R.string.search_k_date
}

/** The screen a result opens. */
fun routeOf(kind: SearchKind): String = when (kind) {
    SearchKind.NOTE -> "notes"
    SearchKind.TASK -> "plan"
    SearchKind.LIST -> "lists"
    SearchKind.JOURNAL -> "journal"
    SearchKind.PERSON -> "people"
    SearchKind.TRIP -> "trips"
    SearchKind.ORDER -> "orders"
    SearchKind.PAYMENT, SearchKind.BILL -> "money"
    SearchKind.MEMORY -> "ask"
    SearchKind.DATE -> "inbox"
}
