package app.assistant.domain.engine.subscriptions

import app.assistant.domain.model.SubscriptionInterval
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Due_Date_Rule (Glossary): the date one Subscription_Interval after a given date. It is shared by Bills
 * (Requirement 34.4) and Subscriptions (Requirements 37.1, 38.4, 40.6).
 *
 * Weekly adds 7 days. Monthly, quarterly and yearly add 1, 3 or 12 calendar months and land on [anchorDay], or on the
 * last day of that month when it is shorter. Because the result is always computed from the anchor, never from the
 * previous result, a short month never pulls later dates off the anchor (31 Jan, 29 Feb, 31 Mar, 30 Apr).
 */
object DueDateRule {
    const val MIN_ANCHOR_DAY = 1
    const val MAX_ANCHOR_DAY = 31

    private const val WEEK_DAYS = 7L
    private const val MONTHS_PER_YEAR = 12L
    private const val MONTHS_PER_QUARTER = 3L

    fun next(after: LocalDate, interval: SubscriptionInterval, anchorDay: Int): LocalDate {
        require(anchorDay in MIN_ANCHOR_DAY..MAX_ANCHOR_DAY) { "Anchor_Day is a day of the month" }
        return when (interval) {
            SubscriptionInterval.WEEKLY -> after.plusDays(WEEK_DAYS)
            SubscriptionInterval.MONTHLY -> inMonthsAfter(after, 1L, anchorDay)
            SubscriptionInterval.QUARTERLY -> inMonthsAfter(after, MONTHS_PER_QUARTER, anchorDay)
            SubscriptionInterval.YEARLY -> inMonthsAfter(after, MONTHS_PER_YEAR, anchorDay)
        }
    }

    private fun inMonthsAfter(after: LocalDate, months: Long, anchorDay: Int): LocalDate {
        val month = YearMonth.from(after).plusMonths(months)
        return month.atDay(minOf(anchorDay, month.lengthOfMonth()))
    }
}
