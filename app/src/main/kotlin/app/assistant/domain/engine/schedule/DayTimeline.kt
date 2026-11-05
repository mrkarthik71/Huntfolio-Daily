package app.assistant.domain.engine.schedule

import app.assistant.domain.model.EnergyTag
import app.assistant.domain.model.Routine
import java.time.DayOfWeek

enum class DayItemKind { MEETING, TASK_BLOCK, ROUTINE, FREE }

/** One row of the Today timeline. [refId] is the task id for a block, the routine id for a routine. */
data class DayItem(
    val key: String,
    val title: String,
    val span: Span,
    val kind: DayItemKind,
    val energy: EnergyTag?,
    val done: Boolean = false,
    val refId: Long? = null,
)

/** The day at a glance: the ordered timeline, what covers "now", and how full the working day is. */
data class DayView(
    val items: List<DayItem>,
    val focusNow: DayItem?,
    /** Minutes left in [focusNow], or 0. */
    val focusMinutesLeft: Int,
    /** 0f to 1f progress through [focusNow]. */
    val focusProgress: Float,
    val bookedMinutes: Int,
    val freeMinutes: Int,
    val loadPercent: Int,
    val tasksDone: Int,
    val tasksTotal: Int,
)

/** A planned task block as the timeline needs it. */
data class BlockFacts(val taskId: Long, val title: String, val span: Span, val energy: EnergyTag, val done: Boolean)

/**
 * Builds the Today timeline (Module B/E): meetings, planned task blocks and today's routines in time
 * order, with free stretches of at least [MIN_FREE] minutes inside working hours shown as FREE rows.
 * Pure: the caller passes minutes from local midnight.
 */
object DayTimeline {
    const val MIN_FREE = 30

    fun build(
        weekday: DayOfWeek,
        nowMinute: Int,
        working: Span,
        meetings: List<Pair<String, Span>>,
        blocks: List<BlockFacts>,
        routines: List<Routine>,
        tasksDone: Int,
        tasksTotal: Int,
    ): DayView {
        val items = buildList {
            meetings.forEachIndexed { i, (title, span) -> add(DayItem("m$i-${span.start}", title, span, DayItemKind.MEETING, null)) }
            blocks.forEach { b -> add(DayItem("b${b.taskId}-${b.span.start}", b.title, b.span, DayItemKind.TASK_BLOCK, b.energy, b.done, b.taskId)) }
            routines.filter { it.active && weekday in it.days }.forEach { r ->
                val start = r.start.toSecondOfDay() / SECONDS
                val span = Span(start, (start + r.durationMinutes).coerceAtMost(MINUTES_PER_DAY))
                add(DayItem("r${r.id.value}", r.title, span, DayItemKind.ROUTINE, r.energy, done = span.end <= nowMinute, refId = r.id.value))
            }
        }
        val busy = items.map { it.span }
        val free = FreeTime.of(working, busy).filter { it.minutes >= MIN_FREE && it.end > nowMinute }
            .map { f -> Span(maxOf(f.start, nowMinute.coerceIn(working.start, working.end)), f.end) }
            .filter { it.minutes >= MIN_FREE }
        val all = (items + free.map { DayItem("f${it.start}", "", it, DayItemKind.FREE, null) }).sortedWith(compareBy({ it.span.start }, { it.span.end }))
        val focus = all.firstOrNull { it.kind != DayItemKind.FREE && it.span.start <= nowMinute && nowMinute < it.span.end }
        val bookedInWork = FreeTime.of(working, emptyList()).sumOf { w -> w.minutes } - FreeTime.of(working, busy).sumOf { it.minutes }
        val available = working.minutes
        return DayView(
            items = all,
            focusNow = focus,
            focusMinutesLeft = focus?.let { it.span.end - nowMinute } ?: 0,
            focusProgress = focus?.let { (nowMinute - it.span.start).toFloat() / it.span.minutes.coerceAtLeast(1) } ?: 0f,
            bookedMinutes = bookedInWork,
            freeMinutes = (available - bookedInWork).coerceAtLeast(0),
            loadPercent = DayLoad.percent(bookedInWork, available),
            tasksDone = tasksDone,
            tasksTotal = tasksTotal,
        )
    }

    private const val SECONDS = 60
}
