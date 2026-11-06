package app.assistant.domain.engine.life

import app.assistant.domain.model.DateKind
import app.assistant.domain.model.Habit
import app.assistant.domain.model.HabitCheck
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SavingsEntry
import app.assistant.domain.model.SavingsGoal
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Habit schedule, today's progress and streaks. Pure. */
object HabitRules {
    /** The slots [habit] has on [date] (none on a day it does not run, or before it was created). */
    fun slotsOn(habit: Habit, date: LocalDate): List<LocalTime> =
        if (!habit.active || date.isBefore(habit.createdOn) || date.dayOfWeek !in habit.days) emptyList() else habit.times.sorted()

    /** True when every slot of [habit] on [date] is checked (false on a day with no slots). */
    fun fullyDone(habit: Habit, date: LocalDate, checks: Collection<HabitCheck>): Boolean {
        val slots = slotsOn(habit, date)
        if (slots.isEmpty()) return false
        val done = checks.filter { it.habitId == habit.id && it.date == date }.map { it.slot }.toSet()
        return slots.all { it in done }
    }

    /**
     * Consecutive scheduled days fully done, counting back from today. Today still counts as
     * "in progress": an unfinished today does not break the streak, it just is not added.
     */
    fun streak(habit: Habit, checks: Collection<HabitCheck>, today: LocalDate): Int {
        var day = today
        var count = 0
        if (fullyDone(habit, day, checks)) count++
        day = day.minusDays(1)
        var guard = 0
        while (!day.isBefore(habit.createdOn) && guard < MAX_LOOKBACK) {
            guard++
            if (slotsOn(habit, day).isNotEmpty()) {
                if (fullyDone(habit, day, checks)) count++ else break
            }
            day = day.minusDays(1)
        }
        return count
    }

    /** Done and total slots across all habits on [date]. */
    fun dayProgress(habits: List<Habit>, checks: Collection<HabitCheck>, date: LocalDate): Pair<Int, Int> {
        var done = 0
        var total = 0
        for (h in habits) {
            val slots = slotsOn(h, date)
            total += slots.size
            done += slots.count { s -> checks.any { it.habitId == h.id && it.date == date && it.slot == s } }
        }
        return done to total
    }

    private const val MAX_LOOKBACK = 3_660
}

/** One reminder for an important date: [leadDays] before the occurrence on [occurrence]. */
data class DateAlert(val dateId: Long, val occurrence: LocalDate, val leadDays: Int) {
    val fireOn: LocalDate get() = occurrence.minusDays(leadDays.toLong())
    val refKey: String get() = "date:$dateId:$occurrence:$leadDays"
}

/** Next occurrences and reminder lead times for important dates. Pure. */
object DateRules {
    /** How many days ahead each kind is reminded. Documents get a month's notice to renew. */
    fun leadDays(kind: DateKind): List<Int> = when (kind) {
        DateKind.BIRTHDAY, DateKind.ANNIVERSARY, DateKind.OTHER -> listOf(1, 0)
        DateKind.DOCUMENT, DateKind.INSURANCE, DateKind.WARRANTY -> listOf(30, 7, 1, 0)
    }

    /** The next time [d] falls on or after [today]; null for a one-off date already past. 29 Feb falls on 28 Feb in other years. */
    fun nextOccurrence(d: ImportantDate, today: LocalDate): LocalDate? {
        if (!d.yearly) return d.date.takeIf { !it.isBefore(today) }
        var year = today.year
        while (true) {
            val candidate = onYear(d.date, year)
            if (!candidate.isBefore(today)) return candidate
            year++
        }
    }

    private fun onYear(date: LocalDate, year: Int): LocalDate = if (date.monthValue == 2 &&
        date.dayOfMonth == LEAP_DAY &&
        !java.time.Year.isLeap(year.toLong())
    ) {
        LocalDate.of(year, 2, LEAP_DAY - 1)
    } else {
        date.withYear(year)
    }

    fun daysUntil(d: ImportantDate, today: LocalDate): Long? = nextOccurrence(d, today)?.let { ChronoUnit.DAYS.between(today, it) }

    /** Years since the original date at its next occurrence (age on a birthday), when the year is real. */
    fun yearsAt(d: ImportantDate, today: LocalDate): Int? {
        if (!d.yearly || d.date.year <= NO_YEAR) return null
        val next = nextOccurrence(d, today) ?: return null
        return (next.year - d.date.year).takeIf { it > 0 }
    }

    /** Every reminder that fires from [from] to [to] inclusive. */
    fun alertsBetween(dates: List<ImportantDate>, from: LocalDate, to: LocalDate): List<DateAlert> = dates.flatMap { d ->
        val occurrences = buildList {
            nextOccurrence(d, from)?.let { add(it) }
            // A long lead (30 days) can point at the occurrence after this one when the window spans it.
            if (d.yearly) nextOccurrence(d, (lastOrNull() ?: from).plusDays(1))?.let { add(it) }
        }.distinct()
        occurrences.flatMap { occ -> leadDays(d.kind).map { DateAlert(d.id, occ, it) } }
            .filter { !it.fireOn.isBefore(from) && !it.fireOn.isAfter(to) }
    }

    /** Dates whose next occurrence is within [days], soonest first. */
    fun upcoming(dates: List<ImportantDate>, today: LocalDate, days: Long): List<Pair<ImportantDate, LocalDate>> =
        dates.mapNotNull { d -> nextOccurrence(d, today)?.let { d to it } }
            .filter { ChronoUnit.DAYS.between(today, it.second) <= days }
            .sortedBy { it.second }

    private const val LEAP_DAY = 29

    /** Contact birthdays without a year are stored in this year (Android's "--MM-DD" form). */
    const val NO_YEAR = 1904
}

/** Savings progress and pace. Pure; amounts in paise. */
object SavingsRules {
    fun saved(goal: SavingsGoal, entries: List<SavingsEntry>): Paise = entries.filter { it.goalId == goal.id }.fold(Paise(0)) { a, e -> a + e.amount }

    fun progress(goal: SavingsGoal, entries: List<SavingsEntry>): Float =
        if (goal.target.value == 0L) 0f else (saved(goal, entries).value.toFloat() / goal.target.value).coerceIn(0f, 1f)

    /** What must be put aside each month to reach the target by the deadline (null without a deadline). */
    fun monthlyNeeded(goal: SavingsGoal, entries: List<SavingsEntry>, today: LocalDate): Paise? {
        val deadline = goal.deadline ?: return null
        val left = goal.target.value - saved(goal, entries).value
        if (left <= 0) return Paise(0)
        val months = ChronoUnit.MONTHS.between(today.withDayOfMonth(1), deadline.withDayOfMonth(1)).coerceAtLeast(1)
        return Paise((left + months - 1) / months)
    }
}

/** When a category's spending should raise a budget alert. Pure. */
object BudgetAlertRules {
    val THRESHOLDS = listOf(80, 100)

    /** The highest threshold (percent of [budget]) that [after] reaches and [before] did not, or null. */
    fun crossed(budget: Paise, before: Paise, after: Paise): Int? {
        if (budget.value <= 0) return null
        return THRESHOLDS.lastOrNull { t -> after.value * HUNDRED >= budget.value * t && before.value * HUNDRED < budget.value * t }
    }

    private const val HUNDRED = 100L
}

/** A SIP or EMI reminder: [leadDays] before the payment on [on]. */
data class MoneyDue(val holdingId: Long, val on: LocalDate, val leadDays: Int) {
    val refKey: String get() = "due:$holdingId:$on:$leadDays"
}

/** Net worth from the User's holdings. Pure; paise throughout. */
object NetWorthRules {
    data class Summary(val assets: Paise, val liabilities: Paise, val monthlyOut: Paise) {
        /** Assets minus liabilities; negative when debts are larger. */
        val netPaise: Long get() = assets.value - liabilities.value
    }

    fun summary(holdings: List<app.assistant.domain.model.Holding>): Summary {
        val assets = holdings.filter { !it.kind.liability }.fold(Paise(0)) { a, h -> a + h.value }
        val debts = holdings.filter { it.kind.liability }.fold(Paise(0)) { a, h -> a + h.value }
        val monthly = holdings.fold(Paise(0)) { a, h -> a + h.monthly }
        return Summary(assets, debts, monthly)
    }

    /** The next payment date for a holding's [dueDay] on or after [today]. */
    fun nextDue(dueDay: Int, today: LocalDate): LocalDate {
        val day = dueDay.coerceIn(1, MAX_DUE_DAY)
        val thisMonth = today.withDayOfMonth(day)
        return if (thisMonth.isBefore(today)) today.plusMonths(1).withDayOfMonth(day) else thisMonth
    }

    /** SIP and EMI reminders that fire from [from] to [to]: the day before and on the day. */
    fun dueAlerts(holdings: List<app.assistant.domain.model.Holding>, from: LocalDate, to: LocalDate): List<MoneyDue> = holdings
        .filter { it.monthly.value > 0 && it.dueDay != null }
        .flatMap { h ->
            val next = nextDue(h.dueDay!!, from)
            listOf(1, 0).map { MoneyDue(h.id, next, it) } + listOf(1, 0).map { MoneyDue(h.id, nextDue(h.dueDay, next.plusDays(1)), it) }
        }
        .filter { val fire = it.on.minusDays(it.leadDays.toLong()); !fire.isBefore(from) && !fire.isAfter(to) }
        .distinct()

    const val MAX_DUE_DAY = 28
}
