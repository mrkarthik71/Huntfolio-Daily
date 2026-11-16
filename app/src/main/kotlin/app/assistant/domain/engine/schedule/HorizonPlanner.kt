package app.assistant.domain.engine.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How far ahead the User is planning. Rolling windows, so "this week" on a Saturday is still seven days. */
enum class Horizon(val defaultHours: Int) {
    WEEK(5),
    MONTH(20),
    YEAR(100),
}

/** One thing the User wants done in the horizon. [priority] 1 is the most important. */
data class HorizonGoal(val key: String, val name: String, val hours: Int, val priority: Int)

/** How big a step is relative to the goal's other steps. */
enum class StepSize(val weight: Int) {
    S(1),
    M(2),
    L(3),
}

/** A step of a goal, from Gemini or from the built-in breakdown. */
data class HorizonStep(val goalKey: String, val title: String, val size: StepSize = StepSize.M)

/** One work session placed on a day. */
data class HorizonSession(val goalKey: String, val title: String, val date: LocalDate, val minutes: Int)

/** Per goal: how much work it is and the day its last session lands. */
data class GoalOutlook(val key: String, val name: String, val totalMinutes: Int, val sessions: Int, val finish: LocalDate?)

/**
 * The proposal shown before anything is saved: the daily time it takes, whether that fits the time the
 * User can give, when everything is done, and every session by date.
 */
data class HorizonPlan(
    val horizon: Horizon,
    val start: LocalDate,
    val end: LocalDate,
    val daysPerWeek: Int,
    /** Minutes a day the work needs to finish by [end]. */
    val neededDailyMinutes: Int,
    /** Minutes a day actually planned (the need, capped at what the User can give). */
    val dailyMinutes: Int,
    val feasible: Boolean,
    val finish: LocalDate?,
    val goals: List<GoalOutlook>,
    val sessions: List<HorizonSession>,
) {
    val totalMinutes: Int get() = goals.sumOf { it.totalMinutes }
}

/** A planned session that was not done on its day, for [HorizonPlanner.catchUp]. */
data class MissedSession(val id: Long, val minutes: Int, val deadline: LocalDate?)

/**
 * Turns "finish A, B, C and D this month" into a daily plan (pure; the UI saves it only after the User
 * accepts). The work is spread so every goal moves forward each day, the most behind goal first.
 */
object HorizonPlanner {
    const val SLOT = 15
    const val SESSION_MAX = 90
    private const val MINUTES_PER_HOUR = 60
    private const val MAX_DAYS_AHEAD = 3 * 366L
    const val DEFAULT_DAILY_CAP = 120

    fun end(horizon: Horizon, start: LocalDate): LocalDate = when (horizon) {
        Horizon.WEEK -> start.plusDays(6)
        Horizon.MONTH -> start.plusMonths(1).minusDays(1)
        Horizon.YEAR -> start.plusYears(1).minusDays(1)
    }

    /** Work days: Monday onward, [daysPerWeek] of them (5 = Mon–Fri, 6 = Mon–Sat, 7 = every day). */
    fun isWorkDay(date: LocalDate, daysPerWeek: Int): Boolean = date.dayOfWeek.value <= daysPerWeek.coerceIn(1, DayOfWeek.entries.size)

    fun workDays(from: LocalDate, to: LocalDate, daysPerWeek: Int): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        return (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }.filter { isWorkDay(it, daysPerWeek) }
    }

    /**
     * Builds the plan. [steps] may be empty for a goal; then [fallback] gives its built-in steps.
     * [maxDailyMinutes] is the most the User can give a day; when the work needs more, the plan keeps
     * to that and finishes later, and [HorizonPlan.feasible] is false.
     */
    @Suppress("LongParameterList") // every input is a separate choice the User makes
    fun plan(
        horizon: Horizon,
        start: LocalDate,
        goals: List<HorizonGoal>,
        steps: List<HorizonStep>,
        daysPerWeek: Int,
        maxDailyMinutes: Int,
        fallback: (HorizonGoal) -> List<HorizonStep>,
    ): HorizonPlan {
        val end = end(horizon, start)
        val days = workDays(start, end, daysPerWeek).size.coerceAtLeast(1)
        val ordered = goals.filter { it.hours > 0 && it.name.isNotBlank() }.sortedBy { it.priority }
        val total = ordered.sumOf { it.hours * MINUTES_PER_HOUR }
        val needed = roundUp((total + days - 1) / days)
        val cap = roundUp(maxDailyMinutes.coerceAtLeast(SLOT))
        val daily = needed.coerceAtMost(cap).coerceAtLeast(SLOT)
        val queues = ordered.associate { g ->
            val mine = steps.filter { it.goalKey == g.key && it.title.isNotBlank() }.ifEmpty { fallback(g) }
            g.key to ArrayDeque(chunks(g, mine))
        }
        val sessions = schedule(ordered, queues, start, daysPerWeek, daily)
        val outlook = ordered.map { g ->
            val mine = sessions.filter { it.goalKey == g.key }
            GoalOutlook(g.key, g.name, g.hours * MINUTES_PER_HOUR, mine.size, mine.maxOfOrNull { it.date })
        }
        return HorizonPlan(
            horizon = horizon,
            start = start,
            end = end,
            daysPerWeek = daysPerWeek,
            neededDailyMinutes = needed,
            dailyMinutes = daily,
            feasible = needed <= cap,
            finish = sessions.maxOfOrNull { it.date },
            goals = outlook,
            sessions = sessions,
        )
    }

    /** Splits a goal's minutes over its steps by size, then into sessions of at most [SESSION_MAX]. */
    private fun chunks(goal: HorizonGoal, steps: List<HorizonStep>): List<Pair<String, Int>> {
        val total = goal.hours * MINUTES_PER_HOUR
        val weight = steps.sumOf { it.size.weight }.coerceAtLeast(1)
        var left = total
        val out = ArrayList<Pair<String, Int>>()
        steps.forEachIndexed { i, step ->
            val share = if (i == steps.lastIndex) left else roundUp(total * step.size.weight / weight).coerceAtMost(left)
            left -= share
            if (share <= 0) return@forEachIndexed
            val parts = (share + SESSION_MAX - 1) / SESSION_MAX
            var rest = share
            for (p in 1..parts) {
                val m = if (p == parts) rest else roundUp(share / parts).coerceAtMost(rest)
                rest -= m
                if (m > 0) out += (if (parts > 1) "${step.title} ($p/$parts)" else step.title) to m
            }
        }
        return out
    }

    /** Fills each work day up to [daily], always giving the next slot to the goal that is furthest behind. */
    private fun schedule(
        goals: List<HorizonGoal>,
        queues: Map<String, ArrayDeque<Pair<String, Int>>>,
        start: LocalDate,
        daysPerWeek: Int,
        daily: Int,
    ): List<HorizonSession> {
        val totals = queues.mapValues { (_, q) -> q.sumOf { it.second }.coerceAtLeast(1) }
        val done = goals.associate { it.key to 0 }.toMutableMap()
        val out = ArrayList<HorizonSession>()
        var day = start
        val last = start.plusDays(MAX_DAYS_AHEAD)
        while (queues.values.any { it.isNotEmpty() } && !day.isAfter(last)) {
            if (isWorkDay(day, daysPerWeek)) out += fillDay(day, daily, goals, queues, totals, done)
            day = day.plusDays(1)
        }
        return out
    }

    @Suppress("LongParameterList") // the shared running state of [schedule]
    private fun fillDay(
        day: LocalDate,
        daily: Int,
        goals: List<HorizonGoal>,
        queues: Map<String, ArrayDeque<Pair<String, Int>>>,
        totals: Map<String, Int>,
        done: MutableMap<String, Int>,
    ): List<HorizonSession> {
        val out = ArrayList<HorizonSession>()
        var budget = daily
        val worked = HashSet<String>()
        while (budget >= SLOT) {
            // Furthest behind first; a goal already worked today waits while another still has work.
            val pick = goals.filter { queues.getValue(it.key).isNotEmpty() }
                .minWithOrNull(
                    compareBy<HorizonGoal> { it.key in worked }
                        .thenBy { done.getValue(it.key).toDouble() / totals.getValue(it.key) }
                        .thenBy { it.priority },
                ) ?: break
            val q = queues.getValue(pick.key)
            val (title, minutes) = q.removeFirst()
            val take = minOf(minutes, budget)
            if (take < minutes) q.addFirst(title to (minutes - take))
            out += HorizonSession(pick.key, title, day, take)
            done[pick.key] = done.getValue(pick.key) + take
            budget -= take
            worked += pick.key
        }
        return out
    }

    /**
     * Spreads missed sessions over the coming work days: each goes to the first day from [today] with
     * room under [daily]; if none has room before its deadline, to the least loaded day up to it.
     */
    fun catchUp(missed: List<MissedSession>, load: Map<LocalDate, Int>, today: LocalDate, daysPerWeek: Int, daily: Int): Map<Long, LocalDate> {
        val used = load.toMutableMap()
        val result = LinkedHashMap<Long, LocalDate>()
        missed.sortedBy { it.deadline ?: LocalDate.MAX }.forEach { m ->
            val until = m.deadline?.takeIf { !it.isBefore(today) } ?: today.plusDays(DEFAULT_SPREAD_DAYS)
            val days = workDays(today, until, daysPerWeek).ifEmpty { listOf(today) }
            val pick = days.firstOrNull { used.getOrDefault(it, 0) + m.minutes <= daily } ?: days.minBy { used.getOrDefault(it, 0) }
            used[pick] = used.getOrDefault(pick, 0) + m.minutes
            result[m.id] = pick
        }
        return result
    }

    private const val DEFAULT_SPREAD_DAYS = 13L

    private fun roundUp(minutes: Int): Int = ((minutes + SLOT - 1) / SLOT) * SLOT
}
