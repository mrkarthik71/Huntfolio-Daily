package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.model.FollowUp
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.FollowUpStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatShortDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class WaitingViewModel @Inject constructor(private val store: FollowUpStore, time: TimeSource, notify: NotifySwitch) :
    LifeViewModel(notify, NotificationType.FOLLOW_UP) {
    val today: LocalDate = time.today()
    val items: StateFlow<List<FollowUp>> = store.followUps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun add(person: String, about: String, nudgeOn: LocalDate) {
        if (person.isBlank()) return
        replanAfter {
            store.save(FollowUp(0, person, about, nudgeOn, done = false, createdOn = today))
            if (!notifyOn.value) setNotify(true)
        }
    }

    fun setDone(id: Long, done: Boolean) = replanAfter { store.setDone(id, done) }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun WaitingScreen(viewModel: WaitingViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    WaitingContent(items, viewModel.today, notify, viewModel::setNotify, viewModel::setDone, onAdd = { adding = true })
    if (adding) {
        FollowUpSheet(viewModel.today, onDismiss = { adding = false }, onSave = { p, a, d ->
            viewModel.add(p, a, d)
            adding = false
        })
    }
}

@Composable
@Suppress("LongParameterList") // state plus one callback per action
fun WaitingContent(items: List<FollowUp>, today: LocalDate, notify: Boolean, onNotify: (Boolean) -> Unit, onDone: (Long, Boolean) -> Unit, onAdd: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().enterAnimated(0), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption(stringResource(R.string.waiting_caption, items.count { !it.done }))
                    Text(
                        stringResource(R.string.waiting_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                KeyButton(Icons.Filled.Add, stringResource(R.string.waiting_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            NotifyToggle(notify, stringResource(R.string.waiting_notify), stringResource(R.string.waiting_notify_reason), onNotify, Modifier.enterAnimated(1))
        }
        if (items.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.waiting_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        itemsIndexed(items, key = { _, f -> f.id }) { i, f ->
            val overdue = !f.done && !f.nudgeOn.isAfter(today)
            Row(
                Modifier.fillMaxWidth().enterAnimated(
                    i + 2,
                ).alpha(if (f.done) DONE_ALPHA else 1f).raisedPanel(MaterialTheme.shapes.large).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = f.done,
                    onCheckedChange = { onDone(f.id, it) },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.tertiary, checkmarkColor = MaterialTheme.colorScheme.background),
                )
                Column(Modifier.weight(1f)) {
                    Text(f.person, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    if (f.about.isNotBlank()) Text(f.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Caption(
                    formatShortDate(f.nudgeOn),
                    color = if (overdue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    live = overdue,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowUpSheet(today: LocalDate, onDismiss: () -> Unit, onSave: (String, String, LocalDate) -> Unit) {
    var person by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var nudge by remember { mutableStateOf(today.plusDays(DEFAULT_DAYS)) }
    var picking by remember { mutableStateOf(false) }
    LifeSheet(stringResource(R.string.waiting_add), onDismiss) {
        LifeField(person, { person = it }, stringResource(R.string.waiting_person))
        LifeField(about, { about = it }, stringResource(R.string.waiting_about))
        Caption(stringResource(R.string.waiting_nudge))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1L to R.string.waiting_tomorrow, DEFAULT_DAYS to R.string.waiting_three_days, WEEK to R.string.waiting_next_week).forEach { (d, label) ->
                OptionKey(stringResource(label), nudge == today.plusDays(d), { nudge = today.plusDays(d) })
            }
            OptionKey(formatShortDate(nudge), false, { picking = true })
        }
        CredPrimaryButton(stringResource(R.string.life_save), onClick = { onSave(person, about, nudge) }, enabled = person.isNotBlank())
    }
    if (picking) {
        DatePickDialog(nudge, onPick = {
            nudge = it
            picking = false
        }, onDismiss = { picking = false })
    }
}

private const val DEFAULT_DAYS = 3L
private const val WEEK = 7L
private const val DONE_ALPHA = 0.55f
