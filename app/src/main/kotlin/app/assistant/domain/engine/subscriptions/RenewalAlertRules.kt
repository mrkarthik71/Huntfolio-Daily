package app.assistant.domain.engine.subscriptions

import java.time.LocalDate

/**
 * Renewal-alert scheduling rules (Requirement 41). Pure. The alert fires at the morning-brief time on the local date that
 * is Renewal_Lead_Time days before an active Subscription's next due date (41.1). Rows are keyed by (subscription, due
 * date) so at most one alert per due date (41.5). A late-scheduled alert (the alert date has passed) shows within a
 * minute only when the due date is today or later (41.4); otherwise it is skipped.
 */
object RenewalAlertRules {
    const val MIN_LEAD_DAYS = 1
    const val MAX_LEAD_DAYS = 2
    const val DEFAULT_LEAD_DAYS = 2

    /** The local date the renewal alert should be shown: Renewal_Lead_Time days before [nextDue] (Requirement 41.1). */
    fun alertDate(nextDue: LocalDate, leadDays: Int): LocalDate {
        require(leadDays in MIN_LEAD_DAYS..MAX_LEAD_DAYS) { "Renewal_Lead_Time is 1 or 2 days" }
        return nextDue.minusDays(leadDays.toLong())
    }

    /** What to do when scheduling/rescheduling the alert for [nextDue] as of [today] (Requirement 41.4). */
    fun decideSchedule(nextDue: LocalDate, leadDays: Int, today: LocalDate): RenewalAlertDecision {
        val alertOn = alertDate(nextDue, leadDays)
        return when {
            // Normal: the alert date is still in the future — schedule it for the morning brief that day.
            !alertOn.isBefore(today) -> RenewalAlertDecision.Schedule(alertOn)
            // The alert date passed but the renewal has not: show it now (41.4).
            !nextDue.isBefore(today) -> RenewalAlertDecision.ShowNow
            // Both the alert date and the due date have passed: no alert for this due date.
            else -> RenewalAlertDecision.Skip
        }
    }
}

sealed interface RenewalAlertDecision {
    data class Schedule(val on: LocalDate) : RenewalAlertDecision

    data object ShowNow : RenewalAlertDecision

    data object Skip : RenewalAlertDecision
}
