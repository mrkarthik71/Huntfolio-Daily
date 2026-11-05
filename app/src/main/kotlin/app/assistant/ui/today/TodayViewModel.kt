package app.assistant.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.port.AdviceGap
import app.assistant.domain.port.AdviceItem
import app.assistant.domain.port.AdviceUnavailable
import app.assistant.domain.port.Advisor
import app.assistant.domain.port.ReviewAdvice
import app.assistant.domain.port.ReviewFacts
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.calendar.ConflictDetector
import app.assistant.domain.engine.calendar.FocusBlockProposal
import app.assistant.domain.engine.calendar.GapFinder
import app.assistant.domain.engine.calendar.TimelineEntry
import app.assistant.domain.engine.calendar.TodayTimeline
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.GoalStatus
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import app.assistant.domain.model.TimedEvent
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class PriorityRow(val task: Task, val block: TimeBlock?)

/** Everything the Today_Screen shows, in one immutable state (Requirement 15.1). */
data class TodayUi(
    val loading: Boolean = true,
    val date: LocalDate? = null,
    val zone: ZoneId = ZoneId.of("UTC"),
    val priorities: List<PriorityRow> = emptyList(),
    val otherTasks: List<Task> = emptyList(),
    val behindGoals: List<GoalStatus> = emptyList(),
    /** Unpaid Bills due today or within the next 2 days (Requirement 14.6). */
    val dueBills: List<BillOccurrence> = emptyList(),
    val streak: Int = 0,
    /** All-day event titles, listed above the timed events (Requirement 23.2). */
    val allDay: List<String> = emptyList(),
    val timeline: List<TimelineEntry> = emptyList(),
    /** Null until a calendar refresh has succeeded: the screen then shows "calendar not connected". */
    val calendarRefreshedAt: Instant? = null,
    val conflicts: List<ConflictRow> = emptyList(),
    val proposals: List<FocusBlockProposal> = emptyList(),
    /**
     * AI wording for the day, shown after the rule-based plan (Requirement 48.1 to 48.4). Null means
     * no AI wording yet; [aiUnavailable] carries the "AI wording unavailable" indication when a
     * request was made but produced nothing. AI changes to plan order/placement arrive as
     * [aiSuggestions], never applied silently.
     */
    val aiSummary: String? = null,
    val aiSuggestions: List<AiSuggestionRow> = emptyList(),
    val aiUnavailable: Boolean = false,
    /** Apex: the combined timeline (meetings, task blocks, routines, free time), focus-now and day load. */
    val day: app.assistant.domain.engine.schedule.DayView? = null,
    /** Apex: proactive heads-up cards for today and later, newest first. */
    val cards: List<app.assistant.domain.model.ProactiveCard> = emptyList(),
)

/** An AI-proposed change the user can accept or dismiss (Requirement 48.2). */
data class AiSuggestionRow(val text: String, val reason: String)

/** A Time_Block overlapping an event, with the suggested new slot (null means offer removal). */
data class ConflictRow(val block: TimeBlock, val title: String, val suggestion: InstantRange?)

/** The calendar inputs for one date, bundled so the main combine stays readable. */
private data class CalendarSlice(val allDay: List<String>, val timed: List<TimedEvent>, val refreshedAt: Instant?)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val plans: PlanRepository,
    private val tasks: TaskRepository,
    goals: GoalRepository,
    bills: BillRepository,
    private val time: TimeSource,
    private val calendar: CalendarRepository,
    private val settings: SettingsStore,
    private val advisor: Advisor,
    private val planner: app.assistant.domain.port.PlannerRepository,
) : ViewModel() {

    /** Proposals the User dismissed, keyed by their gap: a changed gap is a new proposal (Requirement 25.5). */
    private val dismissed = MutableStateFlow<Set<InstantRange>>(emptySet())

    private fun calendarSlice(date: LocalDate) = combine(calendar.eventsOn(date), calendar.lastSuccessfulRefresh) { day, refreshedAt ->
        CalendarSlice(
            allDay = day.allDay.map { it.title },
            timed = day.timed.mapNotNull { f -> f.timed?.let { TimedEvent(it, f.title) } },
            refreshedAt = refreshedAt,
        )
    }

    /** Re-evaluates "focus now" and the minutes left once a minute. */
    private val minuteTicker = flow {
        var n = 0L
        while (true) {
            emit(n++)
            delay(MINUTE_MS)
        }
    }

    /** Follows the device date: a new day shows its own plan within a minute (Requirement 15.3). */
    private val today = flow {
        while (true) {
            emit(time.today())
            delay(DATE_CHECK_MS)
        }
    }.distinctUntilChanged()

    val state: StateFlow<TodayUi> = today.flatMapLatest { date ->
        flow {
            // The plan is created before it is shown if none exists (Requirement 14.9).
            plans.ensurePlanFor(date)
            val base =
                combine(plans.planFor(date), tasks.plannedOn(date), goals.statuses(date), plans.streak(date), bills.bills()) {
                        stored,
                        planned,
                        goalStatuses,
                        streak,
                        allBills,
                    ->
                    val allBlocks = stored?.plan?.timeBlocks.orEmpty()
                    val blocks = allBlocks.associateBy { it.taskId }
                    val priorities = stored?.priorities.orEmpty().map { PriorityRow(it, blocks[it.id]) }
                    val ids = priorities.map { it.task.id }.toSet()
                    TodayUi(
                        loading = false,
                        date = date,
                        zone = time.zone(),
                        priorities = priorities,
                        otherTasks = planned.filter { it.status == TaskStatus.OPEN && it.id !in ids },
                        behindGoals = goalStatuses.filter { it.isBehind }.sortedByDescending { it.daysBehind },
                        dueBills = allBills.filter { !it.paid && !it.dueDate.isBefore(date) && !it.dueDate.isAfter(date.plusDays(BILL_LEAD_DAYS)) },
                        streak = streak,
                    ) to allBlocks
                }
            val withCal = combine(base, calendarSlice(date), settings.settings, dismissed) { (ui, allBlocks), cal, s, skipped ->
                Triple(withCalendar(ui, allBlocks, cal, s, skipped), allBlocks, cal to s)
            }
            emitAll(
                combine(withCal, planner.routines(), planner.openCards(date), minuteTicker) { (ui, allBlocks, calSettings), routines, cards, _ ->
                    ui.copy(day = dayView(ui, allBlocks, calSettings.first, calSettings.second, routines), cards = cards)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    /** AI wording for the current plan, overlaid onto the rule-based [ruleBasedState] (Requirement 48.1). */
    private val aiAdvice = MutableStateFlow(AiOverlay())

    /** The screen observes this: the rule-based plan, with AI wording layered on top once it arrives. */
    val uiState: StateFlow<TodayUi> = combine(state, aiAdvice) { base, ai ->
        if (base.date == null) base
        else base.copy(aiSummary = ai.summary, aiSuggestions = ai.suggestions, aiUnavailable = ai.unavailable)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    private data class AiOverlay(val summary: String? = null, val suggestions: List<AiSuggestionRow> = emptyList(), val unavailable: Boolean = false)

    /**
     * Requests AI wording for the day AFTER the rule-based plan is shown (Requirement 48.1). The
     * payload names tasks/goals by opaque handles and free gaps as minute windows; wording replaces
     * the template summary on success, placement/order changes become [AiSuggestionRow]s, and a
     * failure sets the "AI wording unavailable" indication. No-op while AI is off (returns at once).
     */
    fun requestAiWording() {
        val ui = state.value
        val date = ui.date ?: return
        viewModelScope.launch {
            val items = buildList {
                ui.priorities.forEachIndexed { i, p -> add(AdviceItem("t${i + 1}", p.task.title)) }
                ui.otherTasks.forEachIndexed { i, t -> add(AdviceItem("o${i + 1}", t.title)) }
            }
            val zone = ui.zone
            val gaps = ui.proposals.mapIndexed { i, p ->
                AdviceGap("gap${i + 1}", p.gap.start.atZone(zone).toLocalTime().toSecondOfDay() / 60, p.gap.end.atZone(zone).toLocalTime().toSecondOfDay() / 60)
            }
            val facts = ReviewFacts(items = items, gaps = gaps, numbers = mapOf("streak" to ui.streak.toString()))
            when (val r = advisor.review(app.assistant.domain.model.PromptType.DAILY_PLAN, facts)) {
                is ReviewAdvice.Wording -> {
                    val suggestions = r.suggestions.map { AiSuggestionRow(it.text, it.reason) }
                    aiAdvice.value = AiOverlay(summary = r.summary, suggestions = suggestions, unavailable = false)
                }
                is ReviewAdvice.Unavailable ->
                    // Disabled is the normal off state: show no indication, just no AI wording.
                    aiAdvice.value = AiOverlay(unavailable = r.reason != AdviceUnavailable.DISABLED)
            }
        }
    }

    private fun withCalendar(
        ui: TodayUi,
        blocks: List<TimeBlock>,
        cal: CalendarSlice,
        settings: app.assistant.domain.model.AppSettings,
        skipped: Set<InstantRange>,
    ): TodayUi {
        val date = ui.date ?: return ui
        val (dayStart, dayEnd) = TodayTimeline.dayWindow(date, ui.zone)
        val now = time.now()
        val gaps = GapFinder.freeGaps(date, now, ui.zone, settings.workingHours, cal.timed, blocks, settings.minFocusLength)
        val titles = (ui.priorities.map { it.task } + ui.otherTasks).associate { it.id to it.title }
        val conflicts = ConflictDetector.conflicts(blocks, cal.timed)
        return ui.copy(
            allDay = cal.allDay,
            timeline = TodayTimeline.order(cal.timed, dayStart, dayEnd),
            calendarRefreshedAt = cal.refreshedAt,
            conflicts = blocks.filter { it.id in conflicts }.map {
                ConflictRow(it, it.taskId?.let(titles::get).orEmpty(), ConflictDetector.suggestMove(it, date, gaps))
            },
            proposals = proposalsFor(gaps.filterNot { it in skipped }, blocks, ui),
        )
    }

    /** The Apex day view, rebuilt whenever the plan, calendar or routines change. */
    private fun dayView(
        ui: TodayUi,
        blocks: List<TimeBlock>,
        cal: CalendarSlice,
        settings: app.assistant.domain.model.AppSettings,
        routines: List<app.assistant.domain.model.Routine>,
    ): app.assistant.domain.engine.schedule.DayView? {
        val date = ui.date ?: return null
        val zone = ui.zone
        fun minuteOf(i: Instant): Int {
            val z = i.atZone(zone)
            return when {
                z.toLocalDate().isBefore(date) -> 0
                z.toLocalDate().isAfter(date) -> app.assistant.domain.engine.schedule.MINUTES_PER_DAY
                else -> z.toLocalTime().toSecondOfDay() / SECONDS_PER_MINUTE
            }
        }
        fun span(r: InstantRange) = app.assistant.domain.engine.schedule.Span(minuteOf(r.start), maxOf(minuteOf(r.start), minuteOf(r.end)))
        val tasksById = (ui.priorities.map { it.task } + ui.otherTasks).associateBy { it.id }
        val blockFacts = blocks.mapNotNull { b ->
            val task = b.taskId?.let(tasksById::get)
            app.assistant.domain.engine.schedule.BlockFacts(
                taskId = b.taskId?.value ?: return@mapNotNull null,
                title = task?.title ?: return@mapNotNull null,
                span = span(b.range),
                energy = task.energy,
                done = task.status == TaskStatus.DONE,
            )
        }
        val all = ui.priorities.map { it.task } + ui.otherTasks
        val wh = settings.workingHours
        val working = if (wh.spansMidnight) {
            app.assistant.domain.engine.schedule.Span(wh.start.toSecondOfDay() / SECONDS_PER_MINUTE, app.assistant.domain.engine.schedule.MINUTES_PER_DAY)
        } else {
            app.assistant.domain.engine.schedule.Span.of(wh.start, wh.end)
        }
        return app.assistant.domain.engine.schedule.DayTimeline.build(
            weekday = date.dayOfWeek,
            nowMinute = minuteOf(time.now()),
            working = working,
            meetings = cal.timed.map { it.title to span(it.range) }.filter { it.second.minutes > 0 },
            blocks = blockFacts,
            routines = routines,
            tasksDone = all.count { it.status == TaskStatus.DONE },
            tasksTotal = all.size,
        )
    }

    fun acceptCard(id: app.assistant.domain.model.CardId) {
        viewModelScope.launch { planner.setCardStatus(id, app.assistant.domain.model.CardStatus.ACCEPTED) }
    }

    fun dismissCard(id: app.assistant.domain.model.CardId) {
        viewModelScope.launch { planner.setCardStatus(id, app.assistant.domain.model.CardStatus.DISMISSED) }
    }

    /** Ranked open Tasks (Priorities first) that have an estimate and no Time_Block yet, fitted into each gap once. */
    private fun proposalsFor(gaps: List<InstantRange>, blocks: List<TimeBlock>, ui: TodayUi): List<FocusBlockProposal> {
        val blocked = blocks.mapNotNull { it.taskId }.toSet()
        val ranked = (ui.priorities.map { it.task } + ui.otherTasks).filter { it.id !in blocked && it.status == TaskStatus.OPEN }
        val used = mutableSetOf<TaskId>()
        return gaps.mapNotNull { gap ->
            GapFinder.proposal(gap, ranked, used)?.also { used += it.taskIds }
        }
    }

    /** Accepts a proposal only if its gap is still free right now; otherwise the next recompute drops it. */
    fun acceptProposal(p: FocusBlockProposal) {
        val ui = state.value
        val date = ui.date ?: return
        viewModelScope.launch {
            val now = time.now()
            val blocks = plans.timeBlocks(date)
            val events = calendar.eventsOn(date).first().timed.mapNotNull { f -> f.timed?.let { TimedEvent(it, f.title) } }
            val current = settings.current()
            val stillFree = GapFinder.freeGaps(date, now, ui.zone, current.workingHours, events, blocks, current.minFocusLength).any {
                !it.start.isAfter(maxOf(p.gap.start, now)) && !it.end.isBefore(p.gap.end)
            }
            if (stillFree) plans.addBlocks(date, GapFinder.place(p, now))
        }
    }

    fun dismissProposal(p: FocusBlockProposal) {
        dismissed.value = dismissed.value + p.gap
    }

    /** Moves a conflicting block to the suggested slot; nothing changes until the User asks (Requirement 23.6). */
    fun acceptMove(row: ConflictRow) {
        val id: TimeBlockId = row.block.id ?: return
        val to = row.suggestion ?: return
        viewModelScope.launch { plans.moveBlock(id, to) }
    }

    fun removeConflicting(row: ConflictRow) {
        val id: TimeBlockId = row.block.id ?: return
        viewModelScope.launch { plans.removeBlock(id) }
    }

    /** Returns true when a task was created, so the field can be cleared (Requirement 13.3, 13.4). */
    fun quickAdd(text: String, onResult: (Outcome<*, ValidationError>) -> Unit) {
        viewModelScope.launch { onResult(tasks.quickAdd(text, time.now(), time.today())) }
    }

    fun markDone(id: TaskId) {
        viewModelScope.launch { tasks.markDone(id, time.now(), time.today()) }
    }

    fun move(id: TaskId, delta: Int) {
        val ui = state.value
        val date = ui.date ?: return
        val ids = ui.priorities.map { it.task.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { plans.setPriorities(date, ids) }
    }

    fun makePriority(id: TaskId) {
        val ui = state.value
        val date = ui.date ?: return
        if (ui.priorities.size >= MAX_PRIORITIES) return
        viewModelScope.launch { plans.setPriorities(date, ui.priorities.map { it.task.id } + id) }
    }

    fun removePriority(id: TaskId) {
        val date = state.value.date ?: return
        viewModelScope.launch { plans.removePriority(date, id) }
    }

    private companion object {
        const val DATE_CHECK_MS = 30_000L
        const val SECONDS_PER_MINUTE = 60
        const val MINUTE_MS = 60_000L
        const val BILL_LEAD_DAYS = 2L
        const val MAX_PRIORITIES = 3
    }
}
