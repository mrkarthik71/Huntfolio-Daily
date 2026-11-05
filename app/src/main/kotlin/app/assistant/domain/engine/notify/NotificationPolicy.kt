package app.assistant.domain.engine.notify

import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.NotificationType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A notification some feature wants to show. [refKey] holds IDs only, never message text. */
data class Candidate(
    val type: NotificationType,
    val refKey: String,
    val createdAt: Instant,
    /** For event reminders: the event start. Stale once it has passed. */
    val eventStart: Instant? = null,
    /** For bills and renewals: the due date. Stale once it is before today. */
    val dueDate: LocalDate? = null,
    val isSnoozeRepeat: Boolean = false,
)

/** Counts for the current local day (midnight to midnight). */
data class GovernorState(val day: LocalDate, val shownToday: Int, val shownTodayByType: Map<NotificationType, Int>)

data class GovernorSettings(
    val enabled: Set<NotificationType>,
    val quietHours: LocalTimeRange?,
    val dailyCap: Int,
    val perTypeLimit: Map<NotificationType, Int>,
    val capExempt: Set<NotificationType>,
    val defaultPerTypeLimit: Int = 3,
) {
    fun limit(type: NotificationType): Int = perTypeLimit[type] ?: defaultPerTypeLimit
}

enum class InboxReason { STALE, PER_TYPE_LIMIT, DAILY_CAP }

sealed interface Decision {
    /** Show and count toward the Daily_Cap and the per-type limit. */
    data object Show : Decision

    /** Show without counting toward the Daily_Cap: snooze repeats and Cap_Exempt_Types. */
    data class ShowUncounted(val countsForType: Boolean) : Decision

    data class Hold(val until: Instant) : Decision

    data class ToInbox(val reason: InboxReason) : Decision

    /** The type is switched off: not shown and not in the Inbox_List (Requirement 18.9). */
    data object Drop : Decision
}

/**
 * The single notification policy (ADR 0010). Pure: the same candidate, state, settings, time and
 * zone always give the same decision. Order follows the design's decision flow.
 */
object NotificationPolicy {

    // A gatekeeper with one early return per rejection rule reads far clearer than a nested chain.
    @Suppress("ReturnCount")
    fun decide(c: Candidate, s: GovernorState, cfg: GovernorSettings, now: Instant, zone: ZoneId): Decision {
        if (c.type !in cfg.enabled) return Decision.Drop
        if (isStale(c, now, zone)) return Decision.ToInbox(InboxReason.STALE)
        cfg.quietHours?.let { q ->
            quietHoursEnd(now, zone, q)?.let { end -> return Decision.Hold(end) }
        }
        val today = now.atZone(zone).toLocalDate()
        val shown = if (s.day == today) s.shownToday else 0
        val shownOfType = if (s.day == today) s.shownTodayByType[c.type] ?: 0 else 0
        // Snooze repeats do not count again (Requirement 18.5).
        if (c.isSnoozeRepeat) return Decision.ShowUncounted(countsForType = false)
        // Per-type limit applies to every type, including exempt ones (Requirement 18.8).
        if (shownOfType >= cfg.limit(c.type)) return Decision.ToInbox(InboxReason.PER_TYPE_LIMIT)
        if (c.type in cfg.capExempt) return Decision.ShowUncounted(countsForType = true)
        if (shown >= cfg.dailyCap) return Decision.ToInbox(InboxReason.DAILY_CAP)
        return Decision.Show
    }

    /** Stale items go to the Inbox_List (Requirements 18.3, 19.5, 24.6). */
    fun isStale(c: Candidate, now: Instant, zone: ZoneId): Boolean {
        c.eventStart?.let { if (!now.isBefore(it)) return true }
        c.dueDate?.let { if (it.isBefore(now.atZone(zone).toLocalDate())) return true }
        return false
    }

    /**
     * If [now] is inside Quiet_Hours, the instant they end; otherwise null. Handles windows that
     * span midnight (22:30 to 07:00): the end is today or tomorrow depending on the side of midnight.
     */
    fun quietHoursEnd(now: Instant, zone: ZoneId, quiet: LocalTimeRange): Instant? {
        val local: LocalDateTime = now.atZone(zone).toLocalDateTime()
        val time = local.toLocalTime()
        if (time !in quiet) return null
        val endDate = if (quiet.spansMidnight && time >= quiet.start) local.toLocalDate().plusDays(1) else local.toLocalDate()
        return endDate.atTime(quiet.end).atZone(zone).toInstant()
    }

    /** New counts after a decision; the caller stores them with the notification log. */
    fun apply(s: GovernorState, c: Candidate, d: Decision, today: LocalDate): GovernorState {
        val base = if (s.day == today) s else GovernorState(today, 0, emptyMap())
        fun bumpType() = base.shownTodayByType + (c.type to (base.shownTodayByType[c.type] ?: 0) + 1)
        return when (d) {
            Decision.Show -> base.copy(shownToday = base.shownToday + 1, shownTodayByType = bumpType())
            is Decision.ShowUncounted -> if (d.countsForType) base.copy(shownTodayByType = bumpType()) else base
            else -> base
        }
    }
}
