package app.assistant.notify

import app.assistant.domain.engine.life.DateRules
import app.assistant.domain.engine.life.HabitRules
import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.AlarmStore
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.FollowUpStore
import app.assistant.domain.port.HabitStore
import app.assistant.domain.port.LifeAlarms
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.ReflectionStore
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Reference keys for the whole-day notifications. They hold IDs and dates only, never text (ADR 0009). */
object LifeRefs {
    fun habit(id: Long, date: LocalDate, slot: LocalTime) = "habit:$id:$date:${slot.hour}-${slot.minute}"

    fun followUp(id: Long, on: LocalDate) = "follow:$id:$on"

    fun focus(id: Long) = "focus:$id"

    data class HabitRef(val id: Long, val date: LocalDate, val slot: LocalTime)

    fun parseHabit(ref: String): HabitRef? = runCatching {
        val p = ref.split(':')
        if (p.size != HABIT_PARTS || p[0] != "habit") return null
        val (h, m) = p[3].split('-').map(String::toInt)
        HabitRef(p[1].toLong(), LocalDate.parse(p[2]), LocalTime.of(h, m))
    }.getOrNull()

    fun parseFollowUp(ref: String): Long? = ref.split(':').takeIf { it.size == 3 && it[0] == "follow" }?.get(1)?.toLongOrNull()

    fun parseFocus(ref: String): Long? = ref.removePrefix("focus:").takeIf { ref.startsWith("focus:") }?.toLongOrNull()

    fun parseDate(ref: String): Triple<Long, LocalDate, Int>? = runCatching {
        val p = ref.split(':')
        if (p.size != DATE_PARTS || p[0] != "date") return null
        Triple(p[1].toLong(), LocalDate.parse(p[2]), p[3].toInt())
    }.getOrNull()

    private const val HABIT_PARTS = 4
    private const val DATE_PARTS = 4
}

/**
 * Plans the whole-day notifications into the alarm queue: habit and medicine check-ins at their
 * times (today and tomorrow), important-date reminders and follow-up nudges at the morning-brief
 * time, and the end of a running focus block. Each type is planned only while the User has it on,
 * and the Notification_Governor still decides at fire time.
 */
@Singleton
@Suppress("LongParameterList") // one read port per kind of item planned
class LifeAlarmPlanner @Inject constructor(
    private val alarms: AlarmStore,
    private val log: NotificationLog,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val habits: HabitStore,
    private val dates: DateStore,
    private val followUps: FollowUpStore,
    private val reflection: ReflectionStore,
    private val armer: AlarmArmer,
) : LifeAlarms {

    override suspend fun sync() {
        val s = settings.current()
        val now = time.now()
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        val morning = s.morningBrief
        plan(NotificationType.HABIT_CHECK, NotificationType.HABIT_CHECK in s.enabledTypes) { habitWants(today, zone, now) }
        plan(NotificationType.IMPORTANT_DATE, NotificationType.IMPORTANT_DATE in s.enabledTypes) {
            DateRules.alertsBetween(dates.datesNow(), today, today.plusDays(1)).associate { it.refKey to at(it.fireOn, morning, zone, now) }
        }
        plan(NotificationType.FOLLOW_UP, NotificationType.FOLLOW_UP in s.enabledTypes) {
            followUps.openNow().filter { !it.nudgeOn.isAfter(today.plusDays(1)) }
                .associate { LifeRefs.followUp(it.id, it.nudgeOn) to at(maxOf(it.nudgeOn, today), morning, zone, now) }
        }
        plan(NotificationType.WEEKLY_PLAN, NotificationType.WEEKLY_PLAN in s.enabledTypes) {
            // The next last-day-of-week at 18:00 (today if it is that day and not yet past).
            var day = today
            while (day.dayOfWeek != s.lastDayOfWeek) day = day.plusDays(1)
            var due = day.atTime(WEEKLY_HOUR, 0).atZone(zone).toInstant()
            if (!due.isAfter(now)) due = day.plusWeeks(1).atTime(WEEKLY_HOUR, 0).atZone(zone).toInstant()
            mapOf("week:${due.atZone(zone).toLocalDate()}" to due)
        }
        plan(NotificationType.FOCUS_END, NotificationType.FOCUS_END in s.enabledTypes) {
            reflection.running()?.let { mapOf(LifeRefs.focus(it.id) to it.startedAt.plus(Duration.ofMinutes(it.plannedMinutes.toLong()))) } ?: emptyMap()
        }
        armer.arm(alarms.earliest())
    }

    @Suppress("NestedBlockDepth") // days × habits × slots is the natural shape here
    private companion object {
        const val WEEKLY_HOUR = 18
    }

    private suspend fun habitWants(today: LocalDate, zone: ZoneId, now: Instant): Map<String, Instant> {
        val list = habits.habitsNow()
        val checks = habits.checks(today, today.plusDays(1)).first()
        val out = HashMap<String, Instant>()
        for (day in listOf(today, today.plusDays(1))) {
            for (h in list) {
                for (slot in HabitRules.slotsOn(h, day)) {
                    if (checks.any { it.habitId == h.id && it.date == day && it.slot == slot }) continue
                    val due = day.atTime(slot).atZone(zone).toInstant()
                    if (due.isAfter(now)) out[LifeRefs.habit(h.id, day, slot)] = due
                }
            }
        }
        return out
    }

    /** A morning-time item for [day]; one for today whose time has passed fires in a minute. */
    private fun at(day: LocalDate, morning: LocalTime, zone: ZoneId, now: Instant): Instant {
        val due = day.atTime(morning).atZone(zone).toInstant()
        return if (due.isAfter(now)) due else now.plus(Duration.ofMinutes(1))
    }

    /** Makes the queue's non-snooze rows of [type] exactly [wanted] (minus those already shown). */
    private suspend fun plan(type: NotificationType, enabled: Boolean, wanted: suspend () -> Map<String, Instant>) {
        val existing = alarms.all().filter { it.type == type && !it.isSnooze }
        if (!enabled) {
            existing.forEach { alarms.delete(it.id) }
            return
        }
        val handled = log.handledRefKeys(type)
        val want = runCatching { wanted() }.getOrDefault(emptyMap()).filterKeys { it !in handled }
        existing.filter { it.refKey !in want.keys }.forEach { alarms.delete(it.id) }
        val byKey = existing.associateBy { it.refKey }
        for ((key, due) in want) {
            val cur = byKey[key]
            if (cur != null && (cur.dueAt == due || !cur.dueAt.isAfter(time.now()))) continue
            alarms.put(type, key, ScheduleRule.AtInstant(due), due, isSnooze = false)
        }
    }
}
