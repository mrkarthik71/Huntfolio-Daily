package app.assistant.domain.engine.notify

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When a scheduled notification is due. */
sealed interface ScheduleRule {
    /** A User-set clock time on a date (morning brief, evening review): keeps its local time across zone changes. */
    data class AtLocalTime(val date: LocalDate, val time: LocalTime) : ScheduleRule

    /** A fixed point in time (event reminders, snoozes): unaffected by zone changes. */
    data class AtInstant(val instant: Instant) : ScheduleRule
}

object ScheduleRules {
    fun resolve(rule: ScheduleRule, zone: ZoneId): Instant = when (rule) {
        is ScheduleRule.AtLocalTime -> rule.date.atTime(rule.time).atZone(zone).toInstant()
        is ScheduleRule.AtInstant -> rule.instant
    }

    /**
     * Next occurrence of a daily clock time: today if it has not passed yet, otherwise tomorrow
     * (Requirement 20.7). "Not passed" includes the exact minute now.
     */
    fun nextDaily(time: LocalTime, now: Instant, zone: ZoneId): ScheduleRule.AtLocalTime {
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val todayAt = today.atTime(time).atZone(zone).toInstant()
        return if (todayAt.isBefore(now)) ScheduleRule.AtLocalTime(today.plusDays(1), time) else ScheduleRule.AtLocalTime(today, time)
    }

    /** Daily rules whose time passed while the phone was off are moved to their next occurrence. */
    fun rollForward(rule: ScheduleRule.AtLocalTime, now: Instant, zone: ZoneId, graceSeconds: Long = 60): ScheduleRule.AtLocalTime {
        val due = resolve(rule, zone)
        return if (due.plusSeconds(graceSeconds).isBefore(now)) nextDaily(rule.time, now, zone) else rule
    }
}
