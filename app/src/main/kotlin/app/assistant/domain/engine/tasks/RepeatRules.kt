package app.assistant.domain.engine.tasks

import app.assistant.domain.model.Paise
import java.time.DayOfWeek
import java.time.LocalDate

/** How a task repeats. [EVERY_N_DAYS] uses [TaskRepeat.every]. */
enum class RepeatRule { DAILY, WEEKDAYS, WEEKLY, MONTHLY, EVERY_N_DAYS }

data class TaskRepeat(val rule: RepeatRule, val every: Int = 1)

/**
 * The next date of a repeating task once it is done. The next one is counted from its planned date
 * (so "every month on the 5th" stays on the 5th), but never lands on or before the day it was done.
 */
object RepeatRules {
    const val MAX_EVERY = 365

    fun next(repeat: TaskRepeat, planned: LocalDate?, doneOn: LocalDate): LocalDate {
        var d = step(repeat, planned ?: doneOn)
        while (!d.isAfter(doneOn)) d = step(repeat, d)
        return d
    }

    private fun step(r: TaskRepeat, from: LocalDate): LocalDate = when (r.rule) {
        RepeatRule.DAILY -> from.plusDays(1)
        RepeatRule.WEEKLY -> from.plusWeeks(1)
        RepeatRule.MONTHLY -> from.plusMonths(1)
        RepeatRule.EVERY_N_DAYS -> from.plusDays(r.every.coerceIn(1, MAX_EVERY).toLong())
        RepeatRule.WEEKDAYS -> {
            var d = from.plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            d
        }
    }
}

/** Money between the User and one other person. [theyOwe] true: the person owes the User. */
data class Iou(
    val id: Long,
    val person: String,
    val amount: Paise,
    val theyOwe: Boolean,
    val note: String,
    val createdOn: LocalDate,
    val settledOn: LocalDate?,
)

/** One person's open balance in paise: positive when they owe the User, negative when the User owes them. */
data class IouBalance(val person: String, val netPaise: Long, val open: List<Iou>)

object IouRules {
    /** Open entries per person (names matched ignoring case), largest balance first. */
    fun balances(ious: List<Iou>): List<IouBalance> = ious.filter { it.settledOn == null }
        .groupBy { it.person.trim().lowercase() }
        .map { (_, l) ->
            val net = l.sumOf { if (it.theyOwe) it.amount.value else -it.amount.value }
            IouBalance(l.first().person.trim(), net, l.sortedByDescending { it.createdOn })
        }
        .sortedByDescending { kotlin.math.abs(it.netPaise) }

    fun owedToMe(ious: List<Iou>): Paise = Paise(balances(ious).filter { it.netPaise > 0 }.sumOf { it.netPaise })

    fun iOwe(ious: List<Iou>): Paise = Paise(balances(ious).filter { it.netPaise < 0 }.sumOf { -it.netPaise })
}
