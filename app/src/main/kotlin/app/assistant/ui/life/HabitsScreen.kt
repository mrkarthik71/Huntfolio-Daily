package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.HabitRules
import app.assistant.domain.model.Habit
import app.assistant.domain.model.HabitCheck
import app.assistant.domain.model.HabitKind
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.HabitStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.CredPrimaryButton
import app.assistant.ui.designsystem.Dial
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.ReadoutTile
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.designsystem.recessedWell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class HabitRow(val habit: Habit, val slots: List<Pair<LocalTime, Boolean>>, val streak: Int)

data class HabitsUi(val today: LocalDate? = null, val rows: List<HabitRow> = emptyList(), val done: Int = 0, val total: Int = 0, val bestStreak: Int = 0)

@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val store: HabitStore,
    private val time: TimeSource,
    notify: NotifySwitch,
) : LifeViewModel(notify, NotificationType.HABIT_CHECK) {
    private val today: LocalDate = time.today()

    val ui: StateFlow<HabitsUi> = combine(store.habits(), store.checks(today.minusDays(LOOKBACK), today)) { habits, checks -> build(habits, checks) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), HabitsUi())

    private fun build(habits: List<Habit>, checks: List<HabitCheck>): HabitsUi {
        val rows = habits.map { h ->
            val done = checks.filter { it.habitId == h.id && it.date == today }.map { it.slot }.toSet()
            HabitRow(h, HabitRules.slotsOn(h, today).map { it to (it in done) }, HabitRules.streak(h, checks, today))
        }
        val (d, t) = HabitRules.dayProgress(habits, checks, today)
        return HabitsUi(today, rows, d, t, rows.maxOfOrNull { it.streak } ?: 0)
    }

    fun toggle(habitId: Long, slot: LocalTime, done: Boolean) = replanAfter { store.setChecked(habitId, today, slot, done) }

    /** Saving the first habit turns its check-ins on, because the User asked to be reminded. */
    fun save(name: String, kind: HabitKind, times: List<LocalTime>, days: Set<DayOfWeek>, existing: Habit?) {
        if (name.isBlank() || times.isEmpty() || days.isEmpty()) return
        replanAfter {
            store.save(Habit(existing?.id ?: 0, name, kind, times, days, existing?.active ?: true, existing?.createdOn ?: today))
            if (existing == null && !notifyOn.value) setNotify(true)
        }
    }

    fun delete(id: Long) = replanAfter { store.delete(id) }

    private companion object {
        const val LOOKBACK = 120L
        const val STOP_MS = 5_000L
    }
}

private val slotFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun HabitsScreen(viewModel: HabitsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Habit?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    HabitsContent(ui, notify, onToggle = viewModel::toggle, onNotify = viewModel::setNotify, onAdd = { adding = true }, onEdit = { editing = it })
    if (adding || editing != null) {
        HabitSheet(
            existing = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { name, kind, times, days ->
                viewModel.save(name, kind, times, days, editing)
                adding = false
                editing = null
            },
            onDelete = editing?.let { h ->
                {
                    viewModel.delete(h.id)
                    editing = null
                }
            },
        )
    }
}

@Composable
@Suppress("LongParameterList") // state plus one callback per action on the screen
fun HabitsContent(
    ui: HabitsUi,
    notify: Boolean,
    onToggle: (Long, LocalTime, Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Habit) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().enterAnimated(0).raisedPanel(MaterialTheme.shapes.extraLarge, elevation = 18.dp).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Dial(if (ui.total == 0) 0f else ui.done.toFloat() / ui.total, "${ui.done}/${ui.total}", stringResource(R.string.habits_dial))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Caption(stringResource(R.string.habits_caption), color = MaterialTheme.colorScheme.primary, live = ui.done < ui.total)
                    Text(
                        if (ui.total == 0) stringResource(R.string.habits_none_today) else stringResource(R.string.habits_left, ui.total - ui.done),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                KeyButton(Icons.Filled.Add, stringResource(R.string.habits_add), onClick = onAdd, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item { ReadoutTile(stringResource(R.string.habits_best_streak), stringResource(R.string.habits_days, ui.bestStreak), Modifier.fillMaxWidth().enterAnimated(1)) }
        item { NotifyToggle(notify, stringResource(R.string.habits_notify), stringResource(R.string.habits_notify_reason), onNotify, Modifier.enterAnimated(2)) }
        if (ui.rows.isEmpty()) {
            item { Text(stringResource(R.string.habits_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        }
        itemsIndexed(ui.rows, key = { _, r -> r.habit.id }) { i, row -> HabitCard(row, onToggle, onEdit, Modifier.enterAnimated(i + 3)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitCard(row: HabitRow, onToggle: (Long, LocalTime, Boolean) -> Unit, onEdit: (Habit) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption(stringResource(if (row.habit.kind == HabitKind.MEDICINE) R.string.habits_kind_medicine else R.string.habits_kind_habit))
                Text(row.habit.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            Text(stringResource(R.string.habits_streak_short, row.streak), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            TextButton(onClick = { onEdit(row.habit) }) { Text(stringResource(R.string.life_edit), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (row.slots.isEmpty()) {
            Text(stringResource(R.string.habits_rest_day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row.slots.forEach { (slot, done) ->
                    Box(
                        Modifier.heightIn(min = 44.dp)
                            .let { if (done) it.recessedWell(MaterialTheme.shapes.small, deep = true) else it.raisedPanel(MaterialTheme.shapes.small, elevation = 4.dp) }
                            .toggleable(done, role = Role.Checkbox) { onToggle(row.habit.id, slot, it) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (if (done) "✓ " else "") + slot.format(slotFormat),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitSheet(
    existing: Habit?,
    onDismiss: () -> Unit,
    onSave: (String, HabitKind, List<LocalTime>, Set<DayOfWeek>) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: HabitKind.HABIT) }
    var times by remember { mutableStateOf(existing?.times ?: listOf(LocalTime.of(DEFAULT_HOUR, 0))) }
    var days by remember { mutableStateOf(existing?.days ?: DayOfWeek.entries.toSet()) }
    var picking by remember { mutableStateOf(false) }
    LifeSheet(stringResource(if (existing == null) R.string.habits_add else R.string.habits_edit), onDismiss) {
        LifeField(name, { name = it }, stringResource(R.string.habits_name))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionKey(stringResource(R.string.habits_kind_habit), kind == HabitKind.HABIT, { kind = HabitKind.HABIT })
            OptionKey(stringResource(R.string.habits_kind_medicine), kind == HabitKind.MEDICINE, { kind = HabitKind.MEDICINE })
        }
        Caption(stringResource(R.string.habits_times))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            times.sorted().forEach { t -> OptionKey(t.format(slotFormat) + "  ×", true, { if (times.size > 1) times = times - t }) }
            OptionKey(stringResource(R.string.habits_add_time), false, { picking = true })
        }
        Caption(stringResource(R.string.habits_days_caption))
        DayToggles(days) { d -> days = if (d in days) days - d else days + d }
        CredPrimaryButton(stringResource(R.string.life_save), onClick = { onSave(name, kind, times, days) }, enabled = name.isNotBlank() && days.isNotEmpty())
        if (onDelete != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.life_delete), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (picking) {
        TimePickDialog(LocalTime.of(DEFAULT_HOUR, 0), onPick = {
            times = (times + it).distinct()
            picking = false
        }, onDismiss = { picking = false })
    }
}

private const val DEFAULT_HOUR = 9
