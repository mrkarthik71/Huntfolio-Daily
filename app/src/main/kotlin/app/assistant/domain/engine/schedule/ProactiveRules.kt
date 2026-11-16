package app.assistant.domain.engine.schedule

import app.assistant.domain.model.CardKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the hourly agent knows about one day: minutes planned (tasks + meetings) and minutes free to work. */
data class DayFacts(val date: LocalDate, val plannedMinutes: Int, val availableMinutes: Int, val largestFreeMinutes: Int)

/** A goal with a deadline and the work still estimated for it. */
data class GoalFacts(val id: Long, val name: String, val deadline: LocalDate?, val remainingMinutes: Int)

data class RenewalFacts(val merchant: String, val amountText: String, val dueDate: LocalDate)

/** A card the rules want to show; the worker turns it into words and stores it. */
data class CardSignal(val kind: CardKind, val forDate: LocalDate, val subject: String, val numbers: List<Int>)

/**
 * The proactive agent's rules (Module E). Pure and deterministic, so every card can be explained:
 * - OVERLOAD: tomorrow has more planned than free minutes (numbers: planned, available);
 * - GOAL_AT_RISK: remaining estimate is more than [CAPACITY_SHARE] of the free time before the deadline (numbers: remaining, capacity, days left);
 * - RENEWAL_SOON: a subscription renews within [RENEWAL_DAYS] days (numbers: days left);
 * - FREE_SLOT: today still has a free stretch of at least [FREE_SLOT_MIN] minutes and open deep work exists (numbers: minutes).
 */
object ProactiveRules {
    const val RENEWAL_DAYS = 2
    const val FREE_SLOT_MIN = 90
    const val CAPACITY_SHARE_PERCENT = 70

    fun evaluate(
        today: LocalDate,
        days: List<DayFacts>,
        goals: List<GoalFacts>,
        renewals: List<RenewalFacts>,
        averageFreeMinutesPerDay: Int,
        openDeepWork: Boolean,
    ): List<CardSignal> = buildList {
        days.firstOrNull { it.date == today.plusDays(1) }?.let { d ->
            if (d.availableMinutes > 0 && d.plannedMinutes > d.availableMinutes) {
                add(CardSignal(CardKind.OVERLOAD, d.date, "", listOf(d.plannedMinutes, d.availableMinutes)))
            }
        }
        goals.forEach { g ->
            val deadline = g.deadline ?: return@forEach
            val daysLeft = ChronoUnit.DAYS.between(today, deadline).toInt()
            if (daysLeft < 0 || g.remainingMinutes <= 0) return@forEach
            val capacity = (daysLeft + 1) * averageFreeMinutesPerDay * CAPACITY_SHARE_PERCENT / PERCENT
            if (g.remainingMinutes > capacity) add(CardSignal(CardKind.GOAL_AT_RISK, today, g.name, listOf(g.remainingMinutes, capacity, daysLeft)))
        }
        renewals.forEach { r ->
            val daysLeft = ChronoUnit.DAYS.between(today, r.dueDate).toInt()
            if (daysLeft in 0..RENEWAL_DAYS) add(CardSignal(CardKind.RENEWAL_SOON, r.dueDate, r.merchant, listOf(daysLeft)))
        }
        days.firstOrNull { it.date == today }?.let { d ->
            if (openDeepWork && d.largestFreeMinutes >= FREE_SLOT_MIN) add(CardSignal(CardKind.FREE_SLOT, today, "", listOf(d.largestFreeMinutes)))
        }
    }

    private const val PERCENT = 100
}

/** One proposed checkpoint of a goal's pace plan. */
data class PaceStep(val dueDate: LocalDate, val cumulativeTarget: Long, val label: Int)

/**
 * Splits a numeric goal (for example 12 books by 31 Dec) into evenly paced checkpoints, one per
 * [stepDays] (default a fortnight), the last landing on the deadline. Used for "Plan it for me"
 * when AI is off; every number traces back to the goal's target and dates.
 */
object PacePlanner {
    const val DEFAULT_STEP_DAYS = 14
    const val MAX_STEPS = 12

    fun plan(today: LocalDate, deadline: LocalDate, target: Long, alreadyDone: Long, stepDays: Int = DEFAULT_STEP_DAYS): List<PaceStep> {
        val left = (target - alreadyDone).coerceAtLeast(0)
        val days = ChronoUnit.DAYS.between(today, deadline).toInt()
        if (left == 0L || days <= 0) return emptyList()
        val steps = ((days + stepDays - 1) / stepDays).coerceIn(1, MAX_STEPS)
        return (1..steps).map { i ->
            val due = if (i == steps) deadline else today.plusDays(days.toLong() * i / steps)
            val cumulative = alreadyDone + (left * i + steps - 1) / steps
            PaceStep(due, cumulative.coerceAtMost(target), i)
        }
    }
}
