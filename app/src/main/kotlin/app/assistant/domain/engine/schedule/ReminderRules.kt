package app.assistant.domain.engine.schedule

import app.assistant.domain.model.DeviceTrigger
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.model.SmartReminder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When a smart reminder may fire. Pure; the adapters pass the trigger, the clock and the last fire time. */
object ReminderRules {
    /** A phone-state reminder fires on its [trigger] once a day, not before its "not before" time. */
    fun deviceFires(r: SmartReminder, trigger: DeviceTrigger, now: LocalTime, today: LocalDate, lastFired: Instant?, zone: ZoneId): Boolean =
        r.active && r.kind == ReminderKind.DEVICE && r.device == trigger &&
            (r.notBefore == null || !now.isBefore(r.notBefore)) &&
            (lastFired == null || lastFired.atZone(zone).toLocalDate().isBefore(today))

    /** A place reminder fires on the matching transition, at most once an hour so GPS jitter cannot repeat it. */
    fun placeFires(r: SmartReminder, entering: Boolean, now: Instant, lastFired: Instant?): Boolean =
        r.active && r.kind == ReminderKind.PLACE && r.onEnter == entering &&
            (lastFired == null || lastFired.plusSeconds(PLACE_COOLDOWN_SECONDS).isBefore(now))

    /** A time reminder is due when its time has come and it has not fired yet. */
    fun timeDue(r: SmartReminder, now: Instant, lastFired: Instant?): Boolean =
        r.active && r.kind == ReminderKind.TIME && r.fireAt != null && !r.fireAt.isAfter(now) && lastFired == null

    const val PLACE_COOLDOWN_SECONDS = 3_600L
    const val MIN_RADIUS_M = 100
    const val MAX_RADIUS_M = 5_000
}
