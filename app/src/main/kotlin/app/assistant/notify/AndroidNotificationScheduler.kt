package app.assistant.notify

import android.content.Context
import app.assistant.R
import app.assistant.domain.engine.bills.BillNotifications
import app.assistant.domain.engine.calendar.EventReminderPlanner
import app.assistant.domain.engine.notify.BriefContent
import app.assistant.domain.engine.notify.Candidate
import app.assistant.domain.engine.notify.Decision
import app.assistant.domain.engine.notify.GovernorSettings
import app.assistant.domain.engine.notify.NotificationPolicy
import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.engine.notify.ScheduleRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.BillId
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.AlarmStore
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.ImmediateAlert
import app.assistant.domain.port.LogDecision
import app.assistant.domain.port.LogEntry
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.QueuedAlarm
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.RescheduleReason
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TimeSource
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification_Scheduler: an alarm queue of pending items, one system alarm for the earliest, and
 * every item passed through the Notification_Governor policy before it is shown (ADR 0009, 0010).
 * Phase 1 schedules the morning brief, the goal nudge and the evening review.
 */
@Singleton
@Suppress("LongParameterList")
class AndroidNotificationScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarms: AlarmStore,
    private val log: NotificationLog,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val plans: PlanRepository,
    private val goals: GoalRepository,
    private val reports: ReportRepository,
    private val calendar: CalendarRepository,
    private val notifier: Notifier,
    private val armer: AlarmArmer,
    /** Lazy because the Bill repository syncs bill alarms through this scheduler. */
    private val bills: Lazy<BillRepository>,
    /** Lazy: the whole-day planner and content read stores that are only needed when those types are on. */
    private val lifeAlarms: Lazy<app.assistant.domain.port.LifeAlarms>,
    private val life: Lazy<LifeNotifications>,
) : NotificationScheduling {

    override fun hasExactAlarmAccess(): Boolean = armer.hasExactAccess()

    override suspend fun offerNow(alert: ImmediateAlert) {
        val s = settings.current()
        val now = time.now()
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        val content = NotificationContent(alert.title, alert.text, alert.route)
        val candidate = Candidate(alert.type, alert.refKey, now, isSnoozeRepeat = false)
        val ruleId = "now.${alert.type.name.lowercase()}"
        fun entry(decision: LogDecision, counted: Boolean, typeCounted: Boolean) =
            LogEntry(0, today.toString(), alert.type, alert.refKey, decision, ruleId, now, counted, typeCounted, content.text)
        when (val decision = NotificationPolicy.decide(candidate, log.stateFor(today), governor(s), now, zone)) {
            Decision.Drop -> Unit
            // Quiet hours or a full cap: the alert waits in the inbox instead of interrupting.
            is Decision.Hold, is Decision.ToInbox -> log.record(entry(LogDecision.INBOX, counted = false, typeCounted = false))
            Decision.Show, is Decision.ShowUncounted -> {
                if (!notifier.canPost()) {
                    log.record(entry(LogDecision.INBOX, counted = false, typeCounted = false))
                } else {
                    val counted = decision == Decision.Show
                    val typeCounted = counted || (decision is Decision.ShowUncounted && decision.countsForType)
                    val id = log.record(entry(if (counted) LogDecision.SHOWN else LogDecision.SHOWN_UNCOUNTED, counted, typeCounted))
                    notifier.post(id, alert.type, content)
                }
            }
        }
    }

    override suspend fun refresh() {
        val s = settings.current()
        val now = time.now()
        val zone = time.zone()
        for (type in DAILY_TYPES) {
            if (type !in s.enabledTypes) {
                alarms.deleteType(type)
                continue
            }
            val at = dailyTime(type, s)
            val current = alarms.all().filter { it.type == type && !it.isSnooze && it.rule is ScheduleRule.AtLocalTime }
            val keep = current.singleOrNull()?.takeIf { (it.rule as ScheduleRule.AtLocalTime).time == at }
            if (keep == null) {
                // A new time replaces the pending one: today if it has not passed, otherwise tomorrow (Requirement 20.7).
                current.forEach { alarms.delete(it.id) }
                enqueueDaily(type, at, now, zone)
            }
        }
        refreshEventReminders()
        runCatching { lifeAlarms.get().sync() }
        armer.arm(alarms.earliest())
    }

    override suspend fun refreshEventReminders() {
        val s = settings.current()
        val existing = alarms.all().filter { it.type == NotificationType.EVENT_REMINDER && !it.isSnooze && it.refKey.startsWith(EventReminderPlanner.PREFIX) }
        if (NotificationType.EVENT_REMINDER !in s.enabledTypes || !app.assistant.app.FeatureFlags.CALENDAR) {
            existing.forEach { alarms.delete(it.id) }
            armer.arm(alarms.earliest())
            return
        }
        val now = time.now()
        val today = now.atZone(time.zone()).toLocalDate()
        val facts = (0L..CACHE_DAYS).flatMap { calendar.eventsOn(today.plusDays(it)).first().timed }
        val handled = log.handledRefKeys(NotificationType.EVENT_REMINDER)
        val wanted = EventReminderPlanner.plan(facts, s.eventLeadTime, now).filter { it.refKey !in handled }.distinctBy { it.refKey }
        val wantedKeys = wanted.map { it.refKey }.toSet()
        // Removed, moved-to-past and already-handled events lose their reminder (Requirement 24.4).
        existing.filter { it.refKey !in wantedKeys }.forEach { alarms.delete(it.id) }
        val byKey = existing.associateBy { it.refKey }
        for (p in wanted) {
            val current = byKey[p.refKey]
            // A reminder that is already due is left alone so repeated refreshes never push it forward.
            if (current != null && (current.dueAt == p.dueAt || !current.dueAt.isAfter(now))) continue
            alarms.put(NotificationType.EVENT_REMINDER, p.refKey, ScheduleRule.AtInstant(p.dueAt), p.dueAt, isSnooze = false)
        }
        armer.arm(alarms.earliest())
    }
    override suspend fun rescheduleAll(reason: RescheduleReason) {
        val now = time.now()
        val zone = time.zone()
        for (row in alarms.all()) {
            val rule = row.rule
            if (rule is ScheduleRule.AtLocalTime) {
                // A clock-time row keeps its local time in the new zone; after a time or zone change a
                // time that has already passed rolls to the next day. After a reboot a missed one is still shown.
                val next = if (reason == RescheduleReason.TIME_CHANGED || reason == RescheduleReason.ZONE_CHANGED) {
                    ScheduleRules.rollForward(rule, now, zone)
                } else {
                    rule
                }
                alarms.put(row.type, row.refKey, next, ScheduleRules.resolve(next, zone), row.isSnooze)
            }
        }
        refresh()
    }

    override suspend fun onAlarmFired() {
        val now = time.now()
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        runCatching { reports.ensureReportsUpTo(today) }
        val s = settings.current()
        for (row in alarms.due(now.plus(DUE_WINDOW))) process(row, s, now, zone, today)
        refresh()
    }

    override suspend fun snooze(logId: Long, minutes: Int) {
        if (minutes !in SNOOZE_RANGE) return
        val entry = log.get(logId) ?: return
        val due = time.now().plus(Duration.ofMinutes(minutes.toLong()))
        alarms.put(entry.type, "snooze:$logId", ScheduleRule.AtInstant(due), due, isSnooze = true)
        armer.arm(alarms.earliest())
    }

    private suspend fun process(row: QueuedAlarm, s: AppSettings, now: Instant, zone: ZoneId, today: LocalDate) {
        val rule = row.rule
        val isDaily = !row.isSnooze && row.refKey.startsWith(DAILY_PREFIX)
        // A daily item for a past date was missed while the phone was off for days: skip it.
        if (rule is ScheduleRule.AtLocalTime && rule.date.isBefore(today)) {
            alarms.delete(row.id)
            if (isDaily) enqueueDaily(row.type, rule.time, maxOf(now, row.dueAt).plusSeconds(1), zone)
            return
        }
        val content = buildContent(row, today, zone)
        if (content == null) {
            alarms.delete(row.id)
            if (isDaily) enqueueDaily(row.type, dailyTime(row.type, s), maxOf(now, row.dueAt).plusSeconds(1), zone)
            return
        }
        val candidate = Candidate(row.type, row.refKey, row.dueAt, isSnoozeRepeat = row.isSnooze)
        val decision = NotificationPolicy.decide(candidate, log.stateFor(today), governor(s), now, zone)
        if (decision is Decision.Hold) {
            // Keep the item as a fixed point in time so it survives midnight (Requirement 18.10).
            alarms.put(row.type, row.refKey, ScheduleRule.AtInstant(decision.until), decision.until, row.isSnooze)
            return
        }
        alarms.delete(row.id)
        val ruleId = when {
            row.isSnooze -> "snooze"
            row.type == NotificationType.EVENT_REMINDER -> "event.reminder"
            else -> "daily.${row.type.name.lowercase()}"
        }
        when (decision) {
            Decision.Drop -> Unit
            is Decision.ToInbox -> record(row, ruleId, LogDecision.INBOX, counted = false, typeCounted = false, content, now, today)
            is Decision.Hold -> Unit
            Decision.Show, is Decision.ShowUncounted -> {
                if (!notifier.canPost()) {
                    record(row, ruleId, LogDecision.INBOX, counted = false, typeCounted = false, content, now, today)
                } else {
                    val counted = decision == Decision.Show
                    val typeCounted = counted || (decision is Decision.ShowUncounted && decision.countsForType)
                    val id = record(row, ruleId, if (counted) LogDecision.SHOWN else LogDecision.SHOWN_UNCOUNTED, counted, typeCounted, content, now, today)
                    notifier.post(id, row.type, content)
                }
            }
        }
        if (isDaily) enqueueDaily(row.type, dailyTime(row.type, s), maxOf(now, row.dueAt).plusSeconds(1), zone)
        // A bill-due notification is made once per occurrence (Requirement 34.5).
        if (row.type == NotificationType.BILL_DUE) BillNotifications.billIdOf(row.refKey)?.let { bills.get().markNotified(BillId(it)) }
    }

    @Suppress("LongParameterList")
    private suspend fun record(
        row: QueuedAlarm,
        ruleId: String,
        decision: LogDecision,
        counted: Boolean,
        typeCounted: Boolean,
        content: NotificationContent,
        now: Instant,
        today: LocalDate,
    ): Long = log.record(LogEntry(0, today.toString(), row.type, row.refKey, decision, ruleId, now, counted, typeCounted, content.text))

    private suspend fun enqueueDaily(type: NotificationType, at: LocalTime, after: Instant, zone: ZoneId) {
        val rule = ScheduleRules.nextDaily(at, after, zone)
        alarms.put(type, "$DAILY_PREFIX${rule.date}", rule, ScheduleRules.resolve(rule, zone), isSnooze = false)
    }

    private fun governor(s: AppSettings) = GovernorSettings(
        s.enabledTypes,
        s.quietHours,
        s.dailyCap,
        s.perTypeLimit + (NotificationType.GOAL_NUDGE to minOf(s.limitFor(NotificationType.GOAL_NUDGE), BriefContent.GOAL_NUDGE_DAILY_LIMIT)),
        s.capExemptTypes,
    )

    private fun dailyTime(type: NotificationType, s: AppSettings): LocalTime = when (type) {
        NotificationType.EVENING_REVIEW -> s.eveningReview
        else -> s.morningBrief
    }

    /** Null means there is nothing to say now (for example no Goal is behind). */
    private suspend fun buildContent(row: QueuedAlarm, today: LocalDate, zone: ZoneId): NotificationContent? {
        if (row.isSnooze) {
            val entry = row.refKey.removePrefix("snooze:").toLongOrNull()?.let { log.get(it) } ?: return null
            return NotificationContent(context.getString(Notifier.channelName(row.type)), entry.summary, routeFor(row.type, today))
        }
        return when (row.type) {
            NotificationType.MORNING_BRIEF -> {
                val plan = plans.ensurePlanFor(today)
                val brief = BriefContent.morningBrief(plan.priorities.map { it.title })
                val text = if (brief.priorityCount == 0) {
                    context.getString(R.string.brief_none)
                } else {
                    context.resources.getQuantityString(R.plurals.brief_text, brief.priorityCount, brief.priorityCount, brief.firstTitle)
                }
                val extras = runCatching { life.get().briefExtras(today) }.getOrNull()
                NotificationContent(context.getString(R.string.brief_title), if (extras == null) text else "$text\n$extras", routeFor(row.type, today))
            }
            NotificationType.GOAL_NUDGE -> {
                val nudge = BriefContent.goalNudge(goals.statusesNow(today)) ?: return null
                NotificationContent(
                    context.getString(R.string.nudge_title),
                    context.resources.getQuantityString(R.plurals.nudge_text, nudge.daysBehind, nudge.goalName, nudge.daysBehind),
                    routeFor(row.type, today),
                )
            }
            NotificationType.EVENING_REVIEW ->
                NotificationContent(context.getString(R.string.review_title), context.getString(R.string.review_text), routeFor(row.type, today))
            NotificationType.EVENT_REMINDER -> eventContent(row, zone)
            NotificationType.BILL_DUE -> billContent(row)
            else -> life.get().content(row)
        }
    }

    /** The name, amount and due date are read now, so an edited Bill is shown as it is. A paid or deleted Bill says nothing. */
    private suspend fun billContent(row: QueuedAlarm): NotificationContent? {
        val id = BillNotifications.billIdOf(row.refKey) ?: return null
        val bill = bills.get().get(BillId(id))?.takeIf { !it.paid } ?: return null
        val due = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).format(bill.dueDate)
        return NotificationContent(
            context.getString(R.string.bill_due_title),
            context.getString(R.string.bill_due_text, bill.name, MoneyFormatter.format(bill.amount), due),
            "money",
        )
    }

    /** The title and local start time are read from the cache now, so a changed title is shown (Requirement 24.2). */
    private suspend fun eventContent(row: QueuedAlarm, zone: ZoneId): NotificationContent? {
        val (calendarId, eventId, startMillis) = EventReminderPlanner.parse(row.refKey) ?: return null
        val start = Instant.ofEpochMilli(startMillis)
        val fact = calendar.eventsOn(start.atZone(zone).toLocalDate()).first().timed
            .firstOrNull { it.calendarId == calendarId && it.eventId == eventId && it.timed?.start == start } ?: return null
        val title = fact.title.ifBlank { context.getString(R.string.event_untitled) }
        val at = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(start.atZone(zone))
        return NotificationContent(context.getString(R.string.event_reminder_title), context.getString(R.string.event_reminder_text, title, at), "today")
    }
    private fun routeFor(type: NotificationType, today: LocalDate): String = when (type) {
        NotificationType.EVENING_REVIEW -> "review/$today"
        NotificationType.GOAL_NUDGE -> "goals"
        NotificationType.HABIT_CHECK -> "plan"
        NotificationType.IMPORTANT_DATE, NotificationType.FOLLOW_UP -> "inbox"
        NotificationType.BUDGET_ALERT, NotificationType.MONEY_DUE -> "money"
        NotificationType.WEEKLY_PLAN -> "weekplan"
        else -> "today"
    }

    companion object {
        private const val DAILY_PREFIX = "daily:"
        private val DAILY_TYPES = listOf(NotificationType.MORNING_BRIEF, NotificationType.GOAL_NUDGE, NotificationType.EVENING_REVIEW)
        private const val CACHE_DAYS = 7L
        private val DUE_WINDOW: Duration = Duration.ofSeconds(30)
        val SNOOZE_RANGE = 5..1_440
    }
}
