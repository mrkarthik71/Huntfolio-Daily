package app.assistant.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.DateRules
import app.assistant.domain.engine.plan.AgendaDay
import app.assistant.domain.engine.plan.AgendaItem
import app.assistant.domain.engine.plan.AgendaKind
import app.assistant.domain.engine.plan.AgendaRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.FollowUpStore
import app.assistant.domain.port.PersonalStore
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ui.designsystem.AssistantColors
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.LocalReducedMotion
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.pressScale
import app.assistant.ui.designsystem.raisedPanel
import app.assistant.ui.designsystem.recessedWell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class WeekUi(val days: List<AgendaDay> = emptyList(), val loading: Boolean = true)

/**
 * The week ahead: every source the app knows, gathered day by day. It only reads; the one change it
 * offers is ticking a task or plan step done, which the User does by tapping.
 */
@HiltViewModel
@Suppress("LongParameterList") // one read port per kind of thing that can sit on a day
class WeekAheadViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val plans: PlanRepository,
    private val calendar: CalendarRepository,
    private val bills: BillRepository,
    private val dates: DateStore,
    private val followUps: FollowUpStore,
    private val personal: PersonalStore,
    private val time: TimeSource,
) : ViewModel() {
    private val _ui = MutableStateFlow(WeekUi())
    val ui: StateFlow<WeekUi> = _ui.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch { _ui.value = WeekUi(AgendaRules.week(time.today(), gather()), loading = false) }
    }

    fun done(@Suppress("UNUSED_PARAMETER") item: AgendaItem, taskId: Long) {
        viewModelScope.launch {
            runCatching { tasks.markDone(app.assistant.domain.model.TaskId(taskId), time.now(), time.today()) }
            reload()
        }
    }

    /** Task ids behind agenda rows, so a row can be ticked done. */
    private val ids = HashMap<AgendaItem, Long>()

    fun taskIdOf(item: AgendaItem): Long? = ids[item]

    private suspend fun gather(): List<AgendaItem> {
        val zone = time.zone()
        val start = time.today()
        val end = start.plusDays((AgendaRules.DAYS - 1).toLong())
        val out = ArrayList<AgendaItem>()
        ids.clear()
        for (i in 0 until AgendaRules.DAYS) {
            val day = start.plusDays(i.toLong())
            runCatching { dayOf(day, zone, out) }
        }
        runCatching {
            bills.bills().first().filter { !it.dueDate.isBefore(start) && !it.dueDate.isAfter(end) }
                .forEach { out += AgendaItem(AgendaKind.BILL, it.name, it.dueDate, detail = MoneyFormatter.format(it.amount)) }
        }
        runCatching {
            dates.datesNow().forEach { d ->
                val next = DateRules.nextOccurrence(d, start) ?: return@forEach
                if (!next.isAfter(end)) out += AgendaItem(AgendaKind.DATE, d.title, next, detail = d.kind.name)
            }
        }
        runCatching {
            followUps.openNow().filter { !it.nudgeOn.isAfter(end) }.forEach { f ->
                out += AgendaItem(AgendaKind.FOLLOW_UP, f.person, maxOf(f.nudgeOn, start), detail = f.about)
            }
        }
        runCatching {
            personal.trips().first().forEach { t ->
                val at = t.startsAt.atZone(zone)
                val day = at.toLocalDate()
                if (!day.isBefore(start) && !day.isAfter(end)) {
                    val timed = at.toLocalTime() != LocalTime.MIDNIGHT
                    out +=
                        AgendaItem(
                            AgendaKind.TRIP,
                            t.title,
                            day,
                            if (timed) at.toLocalTime() else null,
                            detail = listOfNotNull(t.from, t.to).joinToString(" → "),
                        )
                }
            }
        }
        runCatching {
            personal.orders().first().filter { it.status != OrderStatus.DELIVERED && it.status != OrderStatus.CANCELLED }.forEach { o ->
                val day = o.expectedOn ?: return@forEach
                if (!day.isBefore(start) && !day.isAfter(end)) out += AgendaItem(AgendaKind.DELIVERY, o.merchant, day, detail = o.orderRef)
            }
        }
        return out
    }

    private suspend fun dayOf(day: LocalDate, zone: ZoneId, out: MutableList<AgendaItem>) {
        val events = runCatching { calendar.eventsOn(day).first() }.getOrNull()
        events?.timed?.forEach { e ->
            val r = e.timed ?: return@forEach
            out += AgendaItem(AgendaKind.MEETING, e.title, day, localTime(r.start, zone), minutesBetween(r.start, r.end))
        }
        events?.allDay?.forEach { e -> out += AgendaItem(AgendaKind.ALL_DAY, e.title, day) }
        val blocks = runCatching { plans.timeBlocks(day) }.getOrDefault(emptyList())
        val planned = runCatching { tasks.plannedOn(day).first() }.getOrDefault(emptyList()).filter { it.status != TaskStatus.DROPPED }
        val titles = planned.associate { it.id to it.title }
        blocks.forEach { b ->
            val title = b.taskId?.let(titles::get) ?: return@forEach
            out += AgendaItem(AgendaKind.BLOCK, title, day, localTime(b.range.start, zone), minutesBetween(b.range.start, b.range.end))
        }
        val blocked = blocks.mapNotNull { it.taskId }.toSet()
        planned.filter { it.id !in blocked }.forEach { t ->
            val item = AgendaItem(
                if (t.goalId != null) AgendaKind.STEP else AgendaKind.TASK,
                t.title,
                day,
                minutes = t.estimateMinutes,
                done = t.status == TaskStatus.DONE,
            )
            ids[item] = t.id.value
            out += item
        }
    }

    private fun localTime(i: Instant, zone: ZoneId): LocalTime = i.atZone(zone).toLocalTime().withSecond(0).withNano(0)

    private fun minutesBetween(a: Instant, b: Instant): Int = java.time.Duration.between(a, b).toMinutes().toInt().coerceAtLeast(0)
}

@Composable
fun WeekAheadScreen(onBack: () -> Unit, viewModel: WeekAheadViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    WeekAheadContent(ui, onBack, viewModel::taskIdOf, viewModel::done)
}

/** The screen without its view model, so it can be previewed and screenshot-tested. */
@Composable
fun WeekAheadContent(ui: WeekUi, onBack: () -> Unit, taskIdOf: (AgendaItem) -> Long?, onDone: (AgendaItem, Long) -> Unit, initialDay: Int = 0) {
    var selected by rememberSaveable { mutableIntStateOf(initialDay) }
    val reduced = LocalReducedMotion.current
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(stringResource(R.string.weekahead_caption), Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(
                stringResource(R.string.weekahead_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.enterAnimated(0),
            )
        }
        if (ui.days.isNotEmpty()) {
            item { Summary(ui.days) }
            item { DayStrip(ui.days, selected, onSelect = { selected = it }) }
            item {
                AnimatedContent(
                    selected,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn() togetherWith fadeOut()
                        } else {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally { it / SLIDE_DIV * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / SLIDE_DIV * dir } + fadeOut())
                        }
                    },
                    label = "day",
                ) { i ->
                    val day = ui.days.getOrNull(i) ?: return@AnimatedContent
                    DayTimeline(day, isToday = i == 0, taskIdOf = taskIdOf, onDone = onDone)
                }
            }
        }
    }
}

private const val SLIDE_DIV = 4

/** The week in two lines: total load, the busiest day, and a warning when a day is over-full. */
@Composable
private fun Summary(days: List<AgendaDay>) {
    val busiest = AgendaRules.busiest(days)
    val over = AgendaRules.overloaded(days)
    Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(stringResource(R.string.weekahead_load, hoursLabel(days.sumOf { it.busyMinutes }), days.sumOf { it.dueCount }))
        Text(
            busiest?.let { stringResource(R.string.weekahead_busiest, dayName(it.date, TextStyle.FULL), hoursLabel(it.busyMinutes)) }
                ?: stringResource(R.string.weekahead_light),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (over.isNotEmpty()) {
            Text(
                stringResource(R.string.weekahead_over, over.joinToString(", ") { dayName(it.date, TextStyle.SHORT) }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Seven day keys; each shows its load as a filling bar in a recessed well, and a dot when something is due. */
@Composable
private fun DayStrip(days: List<AgendaDay>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().recessedWell(MaterialTheme.shapes.large).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEachIndexed { i, d ->
            val on = i == selected
            val interaction = remember { MutableInteractionSource() }
            val load by animateFloatAsState(d.load, label = "load")
            val label = stringResource(R.string.weekahead_day_cd, dayName(d.date, TextStyle.FULL), hoursLabel(d.busyMinutes), d.dueCount)
            Column(
                Modifier.weight(1f).heightIn(min = 96.dp)
                    .let { if (on) it.raisedPanel(MaterialTheme.shapes.medium, elevation = 4.dp) else it }
                    .pressScale(interaction)
                    .selectable(on, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(i) }
                    .semantics { contentDescription = label }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    dayName(d.date, TextStyle.NARROW),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Box(Modifier.width(6.dp).height(28.dp).clip(CircleShape).background(AssistantColors.WellDeep), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(load).clip(CircleShape)
                            .background(if (d.busyMinutes > AgendaRules.FULL_DAY_MINUTES) AssistantColors.Alert else AssistantColors.Signal),
                    )
                }
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (d.dueCount > 0) AssistantColors.Info else Color.Transparent))
            }
        }
    }
}

@Composable
private fun DayTimeline(day: AgendaDay, isToday: Boolean, taskIdOf: (AgendaItem) -> Long?, onDone: (AgendaItem, Long) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Caption(
            (if (isToday) stringResource(R.string.weekahead_today) + " · " else "") +
                dayName(day.date, TextStyle.FULL).uppercase() + " · " + hoursLabel(day.busyMinutes),
            Modifier.padding(bottom = 6.dp),
        )
        if (day.items.isEmpty()) {
            Text(stringResource(R.string.weekahead_free), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        day.items.forEach { item -> AgendaRow(item, taskIdOf(item), onDone) }
    }
}

@Composable
private fun AgendaRow(item: AgendaItem, taskId: Long?, onDone: (AgendaItem, Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            item.start?.let { "%02d:%02d".format(it.hour, it.minute) } ?: stringResource(kindShort(item.kind)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(56.dp),
            maxLines = 1,
        )
        Box(Modifier.padding(end = 12.dp).size(width = 4.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)).background(markerOf(item.kind)))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub =
                listOfNotNull(item.minutes?.takeIf { it > 0 }?.let(::hoursLabel), item.detail?.takeIf { it.isNotBlank() }?.let { detailText(item.kind, it) })
            if (sub.isNotEmpty()) {
                Text(
                    sub.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (taskId != null && !item.done) TextButton(onClick = { onDone(item, taskId) }) { Text(stringResource(R.string.horizon_mark_done)) }
    }
}

@Composable
private fun detailText(kind: AgendaKind, detail: String): String = when (kind) {
    AgendaKind.DATE -> when (detail) {
        "BIRTHDAY" -> stringResource(R.string.weekahead_birthday)
        "ANNIVERSARY" -> stringResource(R.string.weekahead_anniversary)
        else -> stringResource(R.string.weekahead_date)
    }
    else -> detail
}

private fun markerOf(kind: AgendaKind): Color = when (kind) {
    AgendaKind.MEETING, AgendaKind.ALL_DAY -> AssistantColors.Neutral
    AgendaKind.BLOCK, AgendaKind.STEP, AgendaKind.TASK -> AssistantColors.Signal
    AgendaKind.BILL, AgendaKind.DELIVERY -> AssistantColors.Info
    AgendaKind.DATE, AgendaKind.FOLLOW_UP -> AssistantColors.Good
    AgendaKind.TRIP -> AssistantColors.SignalHot
}

private fun kindShort(kind: AgendaKind): Int = when (kind) {
    AgendaKind.TRIP -> R.string.weekahead_k_trip
    AgendaKind.MEETING, AgendaKind.ALL_DAY -> R.string.weekahead_k_allday
    AgendaKind.BLOCK, AgendaKind.TASK -> R.string.weekahead_k_task
    AgendaKind.STEP -> R.string.weekahead_k_step
    AgendaKind.BILL -> R.string.weekahead_k_bill
    AgendaKind.DATE -> R.string.weekahead_k_date
    AgendaKind.DELIVERY -> R.string.weekahead_k_delivery
    AgendaKind.FOLLOW_UP -> R.string.weekahead_k_follow
}

private fun dayName(d: LocalDate, style: TextStyle): String = d.dayOfWeek.getDisplayName(style, Locale.getDefault())
