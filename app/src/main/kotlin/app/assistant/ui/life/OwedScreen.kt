package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.tasks.Iou
import app.assistant.domain.engine.tasks.IouBalance
import app.assistant.domain.engine.tasks.IouRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Paise
import app.assistant.domain.port.PersonalStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.AssistantColors
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Money owed between the User and friends or family. Nothing is paid or requested; it is only a record. */
@HiltViewModel
class OwedViewModel @Inject constructor(private val store: PersonalStore, private val time: TimeSource) : ViewModel() {
    val ious: StateFlow<List<Iou>> = store.ious().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun add(person: String, rupees: String, theyOwe: Boolean, note: String) {
        val paise = toPaise(rupees) ?: return
        if (person.isBlank()) return
        viewModelScope.launch { store.addIou(person, Paise(paise), theyOwe, note, time.today()) }
    }

    fun settle(person: String) {
        viewModelScope.launch { store.settlePerson(person, time.today()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { store.deleteIou(id) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** "1,250.50" → 125050 paise; null when it is not a positive amount. */
internal fun toPaise(raw: String): Long? {
    val parts = raw.trim().replace(",", "").split('.')
    val rupees = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
    return (rupees * HUNDRED + frac).takeIf { it in 1..MAX_PAISE }
}

private const val HUNDRED = 100L
private const val MAX_PAISE = 10_000_000_000L

@Composable
fun OwedScreen(onBack: () -> Unit, viewModel: OwedViewModel = hiltViewModel()) {
    val ious by viewModel.ious.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val balances = IouRules.balances(ious)
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.owed_caption), Modifier.padding(start = 14.dp).weight(1f), color = MaterialTheme.colorScheme.primary)
                KeyButton(Icons.Filled.Add, stringResource(R.string.life_add), onClick = { adding = true }, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(
                stringResource(R.string.owed_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.enterAnimated(0),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadoutTile(stringResource(R.string.owed_to_me), MoneyFormatter.format(IouRules.owedToMe(ious)), Modifier.weight(1f))
                ReadoutTile(stringResource(R.string.owed_by_me), MoneyFormatter.format(IouRules.iOwe(ious)), Modifier.weight(1f))
            }
        }
        if (balances.isEmpty()) {
            item { Text(stringResource(R.string.owed_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(balances, key = { it.person.lowercase() }) { b -> BalanceCard(b, onSettle = { viewModel.settle(b.person) }, onDelete = viewModel::delete) }
    }
    if (adding) AddSheet(onDismiss = { adding = false }, onSave = viewModel::add)
}

@Composable
private fun BalanceCard(b: IouBalance, onSettle: () -> Unit, onDelete: (Long) -> Unit) {
    val theyOwe = b.netPaise >= 0
    Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.person, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Text(
                MoneyFormatter.format(Paise(kotlin.math.abs(b.netPaise))),
                style = MaterialTheme.typography.titleMedium,
                color = if (theyOwe) AssistantColors.Good else AssistantColors.Signal,
            )
        }
        Caption(stringResource(if (theyOwe) R.string.owed_they_owe else R.string.owed_you_owe))
        b.open.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(
                        (if (i.theyOwe) "+" else "−") + MoneyFormatter.format(i.amount),
                        i.note.ifBlank { null },
                        formatDate(i.createdOn),
                    ).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(i.id) }) { Text(stringResource(R.string.life_delete)) }
            }
        }
        TextButton(onClick = onSettle) { Text(stringResource(R.string.owed_settle)) }
    }
}

@Composable
private fun AddSheet(onDismiss: () -> Unit, onSave: (String, String, Boolean, String) -> Unit) {
    var person by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var theyOwe by rememberSaveable { mutableStateOf(true) }
    LifeSheet(stringResource(R.string.owed_add), onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionKey(stringResource(R.string.owed_dir_they), theyOwe, { theyOwe = true })
            OptionKey(stringResource(R.string.owed_dir_me), !theyOwe, { theyOwe = false })
        }
        LifeField(person, { person = it }, stringResource(R.string.people_name))
        LifeField(amount, {
            amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(AMOUNT_CHARS)
        }, stringResource(R.string.owed_amount), number = true)
        LifeField(note, { note = it }, stringResource(R.string.owed_note))
        CredPrimaryButton(stringResource(R.string.life_save), onClick = {
            onSave(person, amount, theyOwe, note)
            onDismiss()
        }, enabled = person.isNotBlank() && toPaise(amount) != null)
    }
}

private const val AMOUNT_CHARS = 14
