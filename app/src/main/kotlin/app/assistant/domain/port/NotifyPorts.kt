package app.assistant.domain.port

import app.assistant.domain.engine.notify.GovernorState
import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.model.NotificationType
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A pending scheduled notification. [refKey] holds IDs only, never user text (ADR 0009). */
data class QueuedAlarm(val id: Long, val type: NotificationType, val refKey: String, val rule: ScheduleRule, val dueAt: Instant, val isSnooze: Boolean)

interface AlarmStore {
    /** Adds or replaces the row for (type, refKey). */
    suspend fun put(type: NotificationType, refKey: String, rule: ScheduleRule, dueAt: Instant, isSnooze: Boolean)

    suspend fun all(): List<QueuedAlarm>

    suspend fun due(upTo: Instant): List<QueuedAlarm>

    suspend fun earliest(): QueuedAlarm?

    suspend fun delete(id: Long)

    suspend fun deleteType(type: NotificationType)

    suspend fun setDue(id: Long, dueAt: Instant)
}

enum class LogDecision { SHOWN, SHOWN_UNCOUNTED, INBOX }

/** One governor decision. Feeds the daily counts, the Inbox_List and the Why_Explanation. */
data class LogEntry(
    val id: Long,
    val day: String,
    val type: NotificationType,
    val refKey: String,
    val decision: LogDecision,
    val ruleId: String,
    val at: Instant,
    val counted: Boolean,
    val typeCounted: Boolean,
    /** Text shown in the Inbox_List (titles only, no message text). */
    val summary: String,
)

interface NotificationLog {
    suspend fun record(entry: LogEntry): Long

    suspend fun get(id: Long): LogEntry?

    /** Counts for one local day, in the form the governor needs. */
    suspend fun stateFor(day: java.time.LocalDate): GovernorState

    /** Reference keys already decided for [type] (shown or sent to the Inbox), so a reminder is never repeated. */
    suspend fun handledRefKeys(type: NotificationType): Set<String>

    /** Notifications that did not reach the screen because of the cap or limits, newest first. */
    fun inbox(): Flow<List<LogEntry>>
}

enum class RescheduleReason { BOOT, TIME_CHANGED, ZONE_CHANGED, PACKAGE_REPLACED, EXACT_ALARM_CHANGED, SETTINGS_CHANGED }

/** What UI and receivers can ask of the scheduler (design: Notification_Scheduler). */
interface NotificationScheduling {
    /** Aligns the daily rows with the current settings: new times, switched-off types (Requirements 18.9, 20.7). */
    suspend fun refresh()

    /** Re-resolves clock-time rows after boot, a time change, a zone change or an update (Requirements 19.1, 19.4, 65.5). */
    suspend fun rescheduleAll(reason: RescheduleReason)

    /** Re-plans pending event reminders from the cached calendar and Event_Lead_Time (Requirement 24). */
    suspend fun refreshEventReminders()

    /** Shows everything that is due now, applying the governor, then arms the next alarm. */
    suspend fun onAlarmFired()

    /** Shows the notification again after [minutes] (Requirement 18.5). */
    suspend fun snooze(logId: Long, minutes: Int)

    /** True when exact alarms are available (Requirement 19.3). */
    fun hasExactAlarmAccess(): Boolean
}
