package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.assistant.domain.engine.life.SavingsRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SavingsGoal
import app.assistant.domain.port.SavingsStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.Dial
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.subscriptions.parseRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class SavingsRow(val goal: SavingsGoal, val saved: Paise, val progress: Float, val monthly: Paise?)

/** Savings goals, and the switch for live budget alerts (they belong with money). */
@HiltViewModel
class SavingsViewModel @Inject constructor(private val store: SavingsStore, time: TimeSource, notify: NotifySwitch) :
    LifeViewModel(notify, NotificationType.BUDGET_ALERT) {
    val today: LocalDate = time.today()

    val rows: StateFlow<List<SavingsRow>> = combine(store.goals(), store.entries()) { goals, entries ->
        goals.map { g -> SavingsRow(g, SavingsRules.saved(g, entries), SavingsRules.progress(g, entries), SavingsRules.monthlyNeeded(g, entries, today)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun addGoal(name: String, target: Paise, deadline: LocalDate?) {
        if (name.isBlank() || target.value <= 0) return
        replanAfter { store.save(SavingsGoal(0, name, target, deadline, today)) }
    }

    fun addMoney(goalId: Long, amount: Paise) {
        if (amount.value <= 0) return
        replanAfter { store.addEntry(goalId, amount, today) }
    }

    fun delete(id: Long) = replanAfter { store.delete(id) }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun SavingsScreen(modifier: Modifier = Modifier, viewModel: SavingsViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val alerts by viewModel.notifyOn.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var topUp by remember { mutableStateOf<SavingsGoal?>(null) }
    SavingsContent(rows, alerts, viewModel::setNotify, onAdd = { adding = true }, onTopUp = { topUp = it }, modifier = modifier)
    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        var target by rememberSaveable { mutableStateOf("") }
        var deadline by remember { mutableStateOf<LocalDate?>(null) }
        var picking by remember { mutableStateOf(false) }
        LifeSheet(stringResource(R.string.savings_add), { adding = false }) {
            LifeField(name, { name = it }, stringResource(R.string.savings_name))
            LifeField(target, { target = it }, stringResource(R.string.savings_target), number = true)
            OptionKey(deadline?.let { formatDate(it) } ?: stringResource(R.string.savings_pick_deadline), deadline != null, { picking = true })
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                parseRupees(target)?.let { viewModel.addGoal(name, it, deadline) }
                adding = false
            }, enabled = name.isNotBlank() && (parseRupees(target)?.value ?: 0) > 0)
        }
        if (picking) {
            DatePickDialog(deadline ?: viewModel.today.plusMonths(DEFAULT_MONTHS), onPick = {
                deadline = it
                picking = false
            }, onDismiss = { picking = false })
        }
    }
    topUp?.let { g ->
        var amount by rememberSaveable { mutableStateOf("") }
        LifeSheet(stringResource(R.string.savings_add_money, g.name), { topUp = null }) {
            LifeField(amount, { amount = it }, stringResource(R.string.savings_amount), number = true)
            CredPrimaryButton(stringResource(R.string.life_save), onClick = {
                parseRupees(amount)?.let { viewModel.addMoney(g.id, it) }
                topUp = null
            }, enabled = (parseRupees(amount)?.value ?: 0) > 0)
            TextButton(onClick = {
                viewModel.delete(g.id)
                topUp = null
            }) { Text(stringResource(R.string.savings_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun SavingsContent(
    rows: List<SavingsRow>,
    alerts: Boolean,
    onAlerts: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onTopUp: (SavingsGoal) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            NotifyToggle(
                alerts,
                stringResource(R.string.budget_alerts_title),
                stringResource(R.string.budget_alerts_reason),
                onAlerts,
                Modifier.enterAnimated(0),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().enterAnimated(1), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.savings_caption))
                    Text(
                        stringResource(R.string.savings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                KeyButton(Icons.Filled.Add, stringResource(R.string.savings_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.savings_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        itemsIndexed(rows, key = { _, r -> r.goal.id }) { i, r ->
            Row(
                Modifier.fillMaxWidth().enterAnimated(i + 2).raisedPanel(MaterialTheme.shapes.large).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Dial(r.progress, stringResource(R.string.percent, (r.progress * HUNDRED).toInt()), null, size = 72.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.goal.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        stringResource(R.string.savings_of, MoneyFormatter.format(r.saved), MoneyFormatter.format(r.goal.target)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    r.monthly?.let {
                        val text = if (it.value ==
                            0L
                        ) {
                            stringResource(R.string.savings_reached)
                        } else {
                            stringResource(R.string.savings_monthly, MoneyFormatter.format(it))
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = { onTopUp(r.goal) }) { Text(stringResource(R.string.savings_add_short), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

private const val HUNDRED = 100
private const val DEFAULT_MONTHS = 6L
