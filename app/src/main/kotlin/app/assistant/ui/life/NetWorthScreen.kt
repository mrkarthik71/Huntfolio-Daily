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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import app.assistant.domain.engine.life.NetWorthRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Holding
import app.assistant.domain.model.HoldingKind
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SignedPaise
import app.assistant.domain.port.HoldingStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.ApexProgressBar
import app.assistant.ui.designsystem.AssistantType
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.subscriptions.parseRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val store: HoldingStore,
    time: TimeSource,
    notify: NotifySwitch,
) : LifeViewModel(notify, NotificationType.MONEY_DUE) {
    val today: LocalDate = time.today()
    val holdings: StateFlow<List<Holding>> = store.holdings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun save(existing: Holding?, name: String, kind: HoldingKind, value: Paise, monthly: Paise, dueDay: Int?) {
        if (name.isBlank()) return
        replanAfter {
            store.save(Holding(existing?.id ?: 0, name, kind, value, monthly, dueDay, today))
            if (monthly.value > 0 && dueDay != null && !notifyOn.value) setNotify(true)
        }
    }

    fun delete(id: Long) = replanAfter { store.delete(id) }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun holdingLabel(kind: HoldingKind): String = stringResource(
    when (kind) {
        HoldingKind.BANK -> R.string.nw_bank
        HoldingKind.FIXED_DEPOSIT -> R.string.nw_fd
        HoldingKind.MUTUAL_FUND -> R.string.nw_mf
        HoldingKind.STOCKS -> R.string.nw_stocks
        HoldingKind.PPF_EPF -> R.string.nw_ppf
        HoldingKind.GOLD -> R.string.nw_gold
        HoldingKind.PROPERTY -> R.string.nw_property
        HoldingKind.OTHER_ASSET -> R.string.nw_other_asset
        HoldingKind.LOAN -> R.string.nw_loan
        HoldingKind.CREDIT_CARD -> R.string.nw_card
        HoldingKind.OTHER_DEBT -> R.string.nw_other_debt
    },
)

@Composable
fun NetWorthScreen(modifier: Modifier = Modifier, viewModel: NetWorthViewModel = hiltViewModel()) {
    val list by viewModel.holdings.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Holding?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    NetWorthContent(list, notify, viewModel::setNotify, onAdd = { adding = true }, onEdit = { editing = it }, modifier = modifier)
    if (adding || editing != null) {
        HoldingSheet(editing, onDismiss = {
            adding = false
            editing = null
        }, onSave = { name, kind, value, monthly, day ->
            viewModel.save(editing, name, kind, value, monthly, day)
            adding = false
            editing = null
        }, onDelete = editing?.let { h ->
            {
                viewModel.delete(h.id)
                editing = null
            }
        })
    }
}

@Composable
@Suppress("LongParameterList") // state plus one callback per action
fun NetWorthContent(list: List<Holding>, notify: Boolean, onNotify: (Boolean) -> Unit, onAdd: () -> Unit, onEdit: (Holding) -> Unit, modifier: Modifier = Modifier) {
    val sum = NetWorthRules.summary(list)
    val net = sum.netPaise
    val total = (sum.assets.value + sum.liabilities.value).coerceAtLeast(1)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().enterAnimated(0).raisedPanel(MaterialTheme.shapes.extraLarge, elevation = 18.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(stringResource(R.string.nw_caption), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    KeyButton(Icons.Filled.Add, stringResource(R.string.nw_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    MoneyFormatter.format(SignedPaise(net)),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (net < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                ApexProgressBar(sum.assets.value.toFloat() / total, EnergyColors.Physical, track = MaterialTheme.colorScheme.error.copy(alpha = 0.6f), height = 6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadoutTile(stringResource(R.string.nw_assets), MoneyFormatter.format(sum.assets), Modifier.weight(1f))
                    ReadoutTile(stringResource(R.string.nw_debts), MoneyFormatter.format(sum.liabilities), Modifier.weight(1f))
                }
                if (sum.monthlyOut.value > 0) {
                    Text(stringResource(R.string.nw_monthly_out, MoneyFormatter.format(sum.monthlyOut)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item { NotifyToggle(notify, stringResource(R.string.nw_notify), stringResource(R.string.nw_notify_reason), onNotify, Modifier.enterAnimated(1)) }
        if (list.isEmpty()) item { Text(stringResource(R.string.nw_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        itemsIndexed(list, key = { _, h -> h.id }) { i, h ->
            Row(
                Modifier.fillMaxWidth().enterAnimated(i + 2).raisedPanel(MaterialTheme.shapes.large).clickable { onEdit(h) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Caption(holdingLabel(h.kind), color = if (h.kind.liability) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(h.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    if (h.monthly.value > 0) {
                        val due = h.dueDay?.let { stringResource(R.string.nw_due_day, it) }.orEmpty()
                        Text(stringResource(R.string.nw_monthly, MoneyFormatter.format(h.monthly)) + due, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    (if (h.kind.liability) "−" else "") + MoneyFormatter.format(h.value),
                    style = AssistantType.Readout.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun HoldingSheet(existing: Holding?, onDismiss: () -> Unit, onSave: (String, HoldingKind, Paise, Paise, Int?) -> Unit, onDelete: (() -> Unit)?) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: HoldingKind.MUTUAL_FUND) }
    var value by rememberSaveable { mutableStateOf(existing?.value?.let { (it.value / PAISE).toString() }.orEmpty()) }
    var monthly by rememberSaveable { mutableStateOf(existing?.monthly?.takeIf { it.value > 0 }?.let { (it.value / PAISE).toString() }.orEmpty()) }
    var day by rememberSaveable { mutableStateOf(existing?.dueDay?.toString().orEmpty()) }
    val dayValue = day.toIntOrNull()?.takeIf { it in 1..NetWorthRules.MAX_DUE_DAY }
    LifeSheet(stringResource(if (existing == null) R.string.nw_add else R.string.nw_edit), onDismiss) {
        LifeField(name, { name = it }, stringResource(R.string.nw_name))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldingKind.entries.forEach { k -> OptionKey(holdingLabel(k), kind == k, { kind = k }) }
        }
        LifeField(value, { value = it }, stringResource(if (kind.liability) R.string.nw_owed else R.string.nw_value), number = true)
        LifeField(monthly, { monthly = it }, stringResource(if (kind.liability) R.string.nw_emi else R.string.nw_sip), number = true)
        LifeField(day, { day = it.filter(Char::isDigit).take(2) }, stringResource(R.string.nw_day), number = true, error = if (day.isNotEmpty() && dayValue == null) stringResource(R.string.nw_day_error) else null)
        CredPrimaryButton(
            stringResource(R.string.life_save),
            onClick = { onSave(name, kind, parseRupees(value) ?: Paise(0), parseRupees(monthly) ?: Paise(0), dayValue) },
            enabled = name.isNotBlank() && parseRupees(value) != null,
        )
        if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.life_delete), color = MaterialTheme.colorScheme.error) }
    }
}

private const val PAISE = 100L
