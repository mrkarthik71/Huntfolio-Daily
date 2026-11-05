package app.assistant.domain.engine.bills

import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.engine.notify.ScheduleRules
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.AlarmStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Requirement 34.5: a bill-due notification is shown at the morning brief time on the days a Bill is due within the next 2 days. */
object BillNotifications {
    /** A Bill is announced from this many days before its due date. */
    const val LEAD_DAYS = 2L

    /**
     * The day of the first morning brief time at which the Bill is due within the next 2 days and is not yet past, or
     * null when no such time is left (the due date is already behind). That is 2 days before the due date, or the next
     * morning brief time still ahead when the Bill is entered later than that.
     */
    fun firstDay(due: LocalDate, brief: LocalTime, now: Instant, zone: ZoneId): LocalDate? {
        val next = ScheduleRules.nextDaily(brief, now, zone).date
        val day = maxOf(due.minusDays(LEAD_DAYS), next)
        return day.takeIf { !it.isAfter(due) }
    }

    /** The alarm key of an occurrence. It holds the id only, never the Bill name (ADR 0009). */
    fun refKey(id: Long): String = "$PREFIX$id"

    fun billIdOf(refKey: String): Long? = if (refKey.startsWith(PREFIX)) refKey.removePrefix(PREFIX).toLongOrNull() else null

    const val PREFIX = "bill:"
}

/**
 * Keeps the queued bill-due alarms equal to the Bills that need one: each unpaid occurrence with no earlier bill-due
 * notification has exactly one alarm, and a paid or deleted occurrence has none (Requirements 34.5, 34.7).
 */
class BillAlarms(private val alarms: AlarmStore) {
    /**
     * [handledKeys] are the alarm keys the Notification_Log already decided, so a shown notification is never repeated.
     * [enabled] is false while the BILL_DUE type is switched off, which removes every bill alarm.
     */
    @Suppress("LongParameterList")
    suspend fun sync(bills: List<BillOccurrence>, handledKeys: Set<String>, enabled: Boolean, brief: LocalTime, now: Instant, zone: ZoneId) {
        val wanted: Map<String, LocalDate> = if (!enabled) {
            emptyMap()
        } else {
            bills.filter { !it.paid && !it.notified && BillNotifications.refKey(it.id.value) !in handledKeys }
                .mapNotNull { b -> BillNotifications.firstDay(b.dueDate, brief, now, zone)?.let { BillNotifications.refKey(b.id.value) to it } }
                .toMap()
        }
        val queued = alarms.all().filter { it.type == NotificationType.BILL_DUE && !it.isSnooze && it.refKey.startsWith(BillNotifications.PREFIX) }
        queued.filter { it.refKey !in wanted }.forEach { alarms.delete(it.id) }
        val byKey = queued.associateBy { it.refKey }
        for ((key, day) in wanted) {
            val rule = ScheduleRule.AtLocalTime(day, brief)
            val current = byKey[key]
            // An alarm already due at the right time is left alone so a repeated sync never pushes it forward.
            if (current != null && current.rule == rule) continue
            alarms.put(NotificationType.BILL_DUE, key, rule, ScheduleRules.resolve(rule, zone), isSnooze = false)
        }
    }
}
