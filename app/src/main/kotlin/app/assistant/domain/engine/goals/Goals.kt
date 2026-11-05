package app.assistant.domain.engine.goals

import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.DaySummary
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.Quantity
import app.assistant.domain.model.StreakRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class GoalField { NAME, PERIOD, START_DATE, TARGET, UNIT }

/** What the User typed in the Goal form. The target is the raw text so it can be validated exactly. */
data class GoalInput(val name: String, val period: GoalPeriod?, val startDate: LocalDate?, val targetText: String, val unit: String)

enum class LinkError { WRONG_PERIOD, ARCHIVED, SAME_GOAL, NOT_FOUND }

object GoalValidator {
    const val NAME_MAX = 100
    const val UNIT_MAX = 30

    /** Requirements 11.1 and 11.2. */
    fun validate(input: GoalInput): FieldErrors<GoalField> = fieldErrors {
        val name = input.name.trim()
        check(GoalField.NAME, name.isNotEmpty(), ValidationError.EMPTY)
        check(GoalField.NAME, name.length <= NAME_MAX, ValidationError.TOO_LONG)
        check(GoalField.PERIOD, input.period != null, ValidationError.EMPTY)
        check(GoalField.START_DATE, input.startDate != null, ValidationError.INVALID_DATE)
        when (val t = QuantityParser.parse(input.targetText)) {
            is Outcome.Err -> add(GoalField.TARGET, t.error)
            is Outcome.Ok -> Unit
        }
        val unit = input.unit.trim()
        check(GoalField.UNIT, unit.isNotEmpty(), ValidationError.EMPTY)
        check(GoalField.UNIT, unit.length <= UNIT_MAX, ValidationError.TOO_LONG)
    }

    /** Requirement 11.8: a logged amount from 0.01 to 999,999,999.99. */
    fun validateProgress(raw: String): Outcome<Quantity, ValidationError> = QuantityParser.parse(raw)

    /** Requirements 11.3 and 11.7: week → month or month → year, both active, not itself. */
    fun validateLink(child: Goal, parent: Goal): Outcome<Unit, LinkError> = when {
        child.id == parent.id -> Outcome.Err(LinkError.SAME_GOAL)
        !child.isActive || !parent.isActive -> Outcome.Err(LinkError.ARCHIVED)
        nextLarger(child.period) != parent.period -> Outcome.Err(LinkError.WRONG_PERIOD)
        else -> Outcome.Ok(Unit)
    }

    fun nextLarger(period: GoalPeriod): GoalPeriod? = when (period) {
        GoalPeriod.WEEK -> GoalPeriod.MONTH
        GoalPeriod.MONTH -> GoalPeriod.YEAR
        GoalPeriod.YEAR -> null
    }
}

/**
 * Parses "12", "12.5", "12.50" or "1,234.5" into exact hundredths. Rejects more than two
 * decimals, signs, exponents and anything outside 0.01 to 999,999,999.99.
 */
object QuantityParser {
    private val pattern = Regex("""^(\d{1,3}(,\d{3})+|\d+)(\.(\d{1,2}))?$""")

    fun parse(raw: String): Outcome<Quantity, ValidationError> {
        val text = raw.trim()
        if (text.isEmpty()) return Outcome.Err(ValidationError.EMPTY)
        val m = pattern.matchEntire(text) ?: return Outcome.Err(ValidationError.NOT_A_NUMBER)
        val whole = m.groupValues[1].replace(",", "")
        if (whole.length > 12) return Outcome.Err(ValidationError.OUT_OF_RANGE)
        val fraction = m.groupValues[4].padEnd(2, '0')
        val hundredths = whole.toLong() * 100 + fraction.toLong()
        val q = Quantity(hundredths)
        return if (q.isValidEntry) Outcome.Ok(q) else Outcome.Err(ValidationError.OUT_OF_RANGE)
    }

    /** Formats hundredths for display: 1250 → "12.5", 1200 → "12". */
    fun format(q: Quantity): String {
        val whole = q.hundredths / 100
        val frac = (q.hundredths % 100).toInt()
        return when {
            frac == 0 -> whole.toString()
            frac % 10 == 0 -> "$whole.${frac / 10}"
            else -> "$whole.${frac.toString().padStart(2, '0')}"
        }
    }
}

/** Period_Instance arithmetic. Week instances are the 7 days ending on the User-set last day. */
object Periods {
    fun instanceContaining(period: GoalPeriod, date: LocalDate, lastDayOfWeek: DayOfWeek): PeriodInstance = when (period) {
        GoalPeriod.YEAR -> PeriodInstance(period, date.withDayOfYear(1), date.with(TemporalAdjusters.lastDayOfYear()))
        GoalPeriod.MONTH -> PeriodInstance(period, date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()))
        GoalPeriod.WEEK -> {
            val last = date.with(TemporalAdjusters.nextOrSame(lastDayOfWeek))
            PeriodInstance(period, last.minusDays(6), last)
        }
    }

    /** The week Period_Instance that ended on [lastDay] (the User-set last day of the week). */
    fun weekEndingOn(lastDay: LocalDate): PeriodInstance = PeriodInstance(GoalPeriod.WEEK, lastDay.minusDays(6), lastDay)
}

object ProgressMath {
    /** progress ÷ target × 100, rounded down; may exceed 100 (Requirement 17.1). */
    fun percent(progress: Quantity, target: Quantity): Int {
        require(target.hundredths > 0)
        val p = Math.floorDiv(Math.multiplyExact(progress.hundredths, 100L), target.hundredths)
        return p.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Glossary Days_Behind with exact integer arithmetic (Requirement 17.2):
     * (Expected − actual) ÷ (target ÷ total) = (elapsed × target − actual × total) ÷ target, rounded down.
     * Elapsed is whole days before [today]; both counts start at the later of the instance's
     * first day and the Goal's start date. Zero or negative means on track and returns 0.
     */
    fun daysBehind(goal: Goal, instance: PeriodInstance, progress: Quantity, today: LocalDate): Int {
        val start = maxOf(instance.first, goal.startDate)
        if (start.isAfter(instance.last)) return 0
        val total = ChronoUnit.DAYS.between(start, instance.last) + 1
        val elapsed = ChronoUnit.DAYS.between(start, today).coerceIn(0, total)
        val numerator = Math.subtractExact(
            Math.multiplyExact(elapsed, goal.target.hundredths),
            Math.multiplyExact(progress.hundredths, total),
        )
        return Math.floorDiv(numerator, goal.target.hundredths).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    fun meets(rule: StreakRule, day: DaySummary?): Boolean {
        if (day == null) return false
        return when (rule) {
            StreakRule.ONE_PRIORITY_DONE -> day.prioritiesDone >= 1
            StreakRule.ALL_PRIORITIES_DONE -> day.prioritiesTotal > 0 && day.prioritiesDone >= day.prioritiesTotal
            StreakRule.ANY_TASK_DONE -> day.tasksDone >= 1
        }
    }

    /**
     * Consecutive dates meeting [rule], ending today if today meets it, otherwise ending
     * yesterday (Requirement 17.3). Recomputed from history, so a rule change applies at once (17.4).
     */
    fun streak(history: Map<LocalDate, DaySummary>, rule: StreakRule, today: LocalDate): Int {
        var day = if (meets(rule, history[today])) today else today.minusDays(1)
        var count = 0
        while (meets(rule, history[day])) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
