package app.assistant.domain.engine.schedule

import app.assistant.domain.model.EnergyTag
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** An open task as the planners see it. [minutes] is the estimate (a default when none was given). */
data class PlanTask(val id: Long, val title: String, val minutes: Int, val priority: Int, val energy: EnergyTag, val due: LocalDate?)

data class ProposedBlock(val taskId: Long, val title: String, val span: Span, val energy: EnergyTag)

/** "Plan my day": blocks to add, tasks that did not fit, and whether a short night moved deep work later. */
data class DayProposal(val blocks: List<ProposedBlock>, val unplaced: List<String>, val lowSleep: Boolean)

/**
 * Fills today's free time with open tasks (Module B): free time is working hours minus meetings,
 * existing blocks and routines, from now on. Deep work goes in the energy windows; after a short
 * night ([sleepMinutes] under [LOW_SLEEP]) deep work moves to the afternoon. Pure.
 */
object DayPlanner {
    const val DEFAULT_MINUTES = 30
    const val LOW_SLEEP = 6 * 60

    @Suppress("LongParameterList") // pure planner; each input is a fact about today
    fun propose(tasks: List<PlanTask>, today: LocalDate, nowMinute: Int, working: Span, busy: List<Span>, sleepMinutes: Int? = null): DayProposal {
        val start = roundUp(maxOf(nowMinute, working.start))
        val window = Span(minOf(start, working.end), working.end)
        val free = if (window.minutes <= 0) emptyList() else FreeTime.of(window, busy)
        val lowSleep = sleepMinutes != null && sleepMinutes < LOW_SLEEP
        val windows = if (lowSleep) tiredWindows() else EnergyWindows.Default
        fun work(list: List<PlanTask>) =
            list.map { Work(it.id, it.minutes, it.priority, it.energy, it.due?.let { d -> ChronoUnit.DAYS.between(today, d).toInt() }) }
        val placements = ArrayList<Placement>()
        val unplaced = LinkedHashMap<Long, Int>()
        var open = free
        if (lowSleep) {
            // After a short night deep work is only offered afternoon time, never the morning fallback.
            val (deep, rest) = tasks.partition { it.energy == EnergyTag.DEEP_WORK }
            val afternoon = open.mapNotNull { f -> Span(maxOf(f.start, TIRED_DEEP_START), f.end).takeIf { it.minutes > 0 } }
            val first = SlotAssigner.assign(work(deep), afternoon, windows)
            placements += first.placements
            unplaced += first.unplacedMinutes
            open = open.flatMap { f -> FreeTime.of(f, first.placements.map { it.span }) }
            val second = SlotAssigner.assign(work(rest), open, windows)
            placements += second.placements
            unplaced += second.unplacedMinutes
        } else {
            val result = SlotAssigner.assign(work(tasks), open, windows)
            placements += result.placements
            unplaced += result.unplacedMinutes
        }
        val byId = tasks.associateBy { it.id }
        return DayProposal(
            blocks = placements.sortedBy { it.span.start }.mapNotNull { p -> byId[p.workId]?.let { ProposedBlock(it.id, it.title, p.span, p.energy) } },
            unplaced = unplaced.keys.mapNotNull { byId[it]?.title },
            lowSleep = lowSleep,
        )
    }

    /** After a short night: no deep work before 14:00; admin in the morning. */
    private fun tiredWindows() = EnergyWindows(
        mapOf(
            EnergyTag.DEEP_WORK to listOf(Span(TIRED_DEEP_START, TIRED_DEEP_END)),
            EnergyTag.LIGHT_ADMIN to listOf(Span(TIRED_ADMIN_START, TIRED_DEEP_START)),
            EnergyTag.PHYSICAL_ROUTINE to listOf(Span(EVENING_START, EVENING_END)),
            EnergyTag.REST to emptyList(),
        ),
    )

    private fun roundUp(minute: Int): Int = (minute + STEP - 1) / STEP * STEP

    private const val STEP = 15
    private const val TIRED_ADMIN_START = 9 * 60
    private const val TIRED_DEEP_START = 14 * 60
    private const val TIRED_DEEP_END = 18 * 60
    private const val EVENING_START = 18 * 60
    private const val EVENING_END = 21 * 60
}

/** One task given a day this week. */
data class WeekSlot(val task: PlanTask, val day: LocalDate)

/** The Sunday plan: this week's top tasks, each on a working day, and goals that are behind. */
data class WeekProposal(val weekStart: LocalDate, val top: List<WeekSlot>, val goalsBehind: List<String>)

/**
 * The weekly plan ritual (Module A): choose the week's top [TOP] tasks (most important and soonest
 * due first), then spread them over the working days so no day takes two big ones while another is
 * empty; a task due earlier keeps a day on or before its due date. Pure.
 */
object WeekPlanner {
    const val TOP = 3

    fun propose(tasks: List<PlanTask>, today: LocalDate, goalsBehind: List<String>, workDays: Set<DayOfWeek> = WORK_DAYS): WeekProposal {
        val weekStart = if (today.dayOfWeek == DayOfWeek.MONDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val days = (0L until DAYS_IN_WEEK).map { weekStart.plusDays(it) }.filter { it.dayOfWeek in workDays }
        if (days.isEmpty()) return WeekProposal(weekStart, emptyList(), goalsBehind)
        val ranked = tasks.sortedWith(
            compareBy<PlanTask> { it.priority }.thenBy { it.due ?: LocalDate.MAX }.thenByDescending { it.minutes }.thenBy { it.id },
        ).take(TOP)
        val load = days.associateWith { 0 }.toMutableMap()
        val slots = ranked.map { t ->
            val allowed = days.filter { t.due == null || !it.isAfter(t.due) }.ifEmpty { listOf(days.first()) }
            val day = allowed.minWith(compareBy<LocalDate> { load.getValue(it) }.thenBy { it })
            load[day] = load.getValue(day) + t.minutes
            WeekSlot(t, day)
        }
        return WeekProposal(weekStart, slots.sortedBy { it.day }, goalsBehind)
    }

    private const val DAYS_IN_WEEK = 7L
    private val WORK_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
}
