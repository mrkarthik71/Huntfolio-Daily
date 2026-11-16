package app.assistant.domain.engine.plan

import java.time.LocalDate
import java.time.LocalTime

/** What kind of thing sits on a day. The order is the order shown when times are equal. */
enum class AgendaKind { TRIP, MEETING, BLOCK, TASK, STEP, BILL, DATE, DELIVERY, FOLLOW_UP, ALL_DAY }

/** One thing on a day: a timed item has [start] (and [minutes] when it takes time). */
data class AgendaItem(
    val kind: AgendaKind,
    val title: String,
    val date: LocalDate,
    val start: LocalTime? = null,
    val minutes: Int? = null,
    val detail: String? = null,
    val done: Boolean = false,
)

/** One day of the week view, with how full it is. */
data class AgendaDay(val date: LocalDate, val items: List<AgendaItem>, val busyMinutes: Int, val dueCount: Int) {
    /** 0..1 of a [AgendaRules.FULL_DAY_MINUTES] working day; drives the load bar. */
    val load: Float get() = (busyMinutes.toFloat() / AgendaRules.FULL_DAY_MINUTES).coerceIn(0f, 1f)
}

/**
 * Gathers everything on each of the next seven days into one ordered list per day: timed items by
 * start time first, then the day's untimed items by kind. Busy minutes count meetings, focus blocks
 * and estimated tasks and plan steps that are not done, so the strip shows which days are heavy.
 */
object AgendaRules {
    const val FULL_DAY_MINUTES = 8 * 60
    const val DAYS = 7

    fun week(start: LocalDate, items: List<AgendaItem>): List<AgendaDay> = (0 until DAYS).map { i ->
        val day = start.plusDays(i.toLong())
        val mine = items.filter { it.date == day }.sortedWith(
            compareBy<AgendaItem> { it.start == null }
                .thenBy { it.start ?: LocalTime.MAX }
                .thenBy { it.kind.ordinal }
                .thenBy { it.done }
                .thenBy { it.title.lowercase() },
        )
        val busy = mine.filter { !it.done && it.kind in TIME_TAKING }.sumOf { it.minutes ?: 0 }
        val due = mine.count { it.kind in DUE_KINDS && !it.done }
        AgendaDay(day, mine, busy, due)
    }

    /** The heaviest day, for "Thursday is your busiest day". Null when nothing takes time. */
    fun busiest(days: List<AgendaDay>): AgendaDay? = days.filter { it.busyMinutes > 0 }.maxByOrNull { it.busyMinutes }

    /** Days whose planned work is more than a full working day. */
    fun overloaded(days: List<AgendaDay>): List<AgendaDay> = days.filter { it.busyMinutes > FULL_DAY_MINUTES }

    private val TIME_TAKING = setOf(AgendaKind.MEETING, AgendaKind.BLOCK, AgendaKind.TASK, AgendaKind.STEP)
    private val DUE_KINDS = setOf(AgendaKind.BILL, AgendaKind.DATE, AgendaKind.FOLLOW_UP, AgendaKind.DELIVERY)
}
