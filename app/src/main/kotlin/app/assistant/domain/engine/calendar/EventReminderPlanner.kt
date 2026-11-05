package app.assistant.domain.engine.calendar

import app.assistant.domain.model.CalendarEventFact
import java.time.Duration
import java.time.Instant

/** One pending event reminder: due at `event.timed.start - leadTime`, or `floor` when that is past. */
data class EventReminderPlan(val refKey: String, val title: String, val dueAt: Instant)

/**
 * Pure planning for Calendar_Event reminders (Requirement 24, design Property 28). The reminder
 * set is recomputed from the cached timed events whenever the cache or Event_Lead_Time changes;
 * the caller (Notification_Scheduler) diffs this against the stored queue and keeps a reminder
 * already shown from reappearing (the caller removes shown keys before comparing).
 */
object EventReminderPlanner {
    const val PREFIX = "event:"

    /** IDs only (ADR 0009): one key per occurrence, since recurring occurrences share an event id. */
    fun refKey(fact: CalendarEventFact, start: Instant): String = "$PREFIX${fact.calendarId}:${fact.eventId}:${start.toEpochMilli()}"

    /** The event's cache key parts, for looking the occurrence up when the reminder is shown. */
    fun parse(refKey: String): Triple<Long, Long, Long>? {
        val parts = refKey.removePrefix(PREFIX).split(':')
        if (!refKey.startsWith(PREFIX) || parts.size != 3) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        return Triple(nums[0], nums[1], nums[2])
    }

    /**
     * One plan per timed, future-starting event not in [shownEventIds]. All-day events and events
     * without a start in the future are excluded. [floor] is the earliest a reminder may be due
     * (now, or now+1min after a refresh that found a near event — Requirement 24.5): a reminder
     * whose computed time has already passed is due at [floor] instead, never in the past.
     */
    fun plan(events: List<CalendarEventFact>, leadTime: Duration, now: Instant, floor: Instant = now): List<EventReminderPlan> = events
        .filter { it.timed != null && !it.cancelled && !it.declined }
        .mapNotNull { fact ->
            val start = fact.timed!!.start
            if (!start.isAfter(now)) return@mapNotNull null
            val due = start.minus(leadTime)
            EventReminderPlan(refKey(fact, start), fact.title, if (due.isBefore(floor)) floor else due)
        }
}
