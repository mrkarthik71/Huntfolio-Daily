package app.assistant.domain.engine.health

import java.time.Duration
import java.time.Instant

/**
 * The Health_Check rules (Requirement 62). Pure: given the last run time of each scheduled job, the
 * permission/notification/channel states and the mailbox state, it decides what is wrong and whether
 * to warn. A job is "missed" when it has not run within its expected interval plus a grace period
 * (30 minutes). The caller gathers the inputs from the platform and shows the single combined
 * warning with fix actions and the Battery_Guide link.
 */
object HealthRules {
    /** The grace period after a job's expected interval before it counts as missed (Requirement 62.1). */
    val MISSED_GRACE: Duration = Duration.ofMinutes(30)

    /** A scheduled job and when it last ran; [expectedInterval] is how often it should run. */
    data class JobState(val job: String, val lastRunAt: Instant?, val expectedInterval: Duration)

    /** One thing that is wrong, with the fix the warning offers. */
    enum class Problem {
        JOB_MISSED,
        NOTIFICATIONS_BLOCKED,
        CHANNEL_BLOCKED,
        PERMISSION_MISSING,
        NOTIFICATION_ACCESS_LOST,
        MAILBOX_NEEDS_RECONNECT,
    }

    /** True when [state] has not run within its interval + the grace period as of [now]. */
    fun jobMissed(state: JobState, now: Instant): Boolean {
        val last = state.lastRunAt ?: return false // never-run is not "missed" until its first window passes elsewhere
        val deadline = last.plus(state.expectedInterval).plus(MISSED_GRACE)
        return now.isAfter(deadline)
    }

    /** The full set of current problems, deterministic and order-independent (Requirement 62.4). */
    fun problems(
        jobs: List<JobState>,
        now: Instant,
        notificationsBlocked: Boolean,
        blockedChannels: Boolean,
        missingPermissions: Boolean,
        notificationAccessLost: Boolean,
        mailboxNeedsReconnect: Boolean,
    ): Set<Problem> = buildSet {
        if (jobs.any { jobMissed(it, now) }) add(Problem.JOB_MISSED)
        if (notificationsBlocked) add(Problem.NOTIFICATIONS_BLOCKED)
        if (blockedChannels) add(Problem.CHANNEL_BLOCKED)
        if (missingPermissions) add(Problem.PERMISSION_MISSING)
        if (notificationAccessLost) add(Problem.NOTIFICATION_ACCESS_LOST)
        if (mailboxNeedsReconnect) add(Problem.MAILBOX_NEEDS_RECONNECT)
    }

    /**
     * The warning state machine (Requirement 62.5, 62.6): warn when there are problems and either
     * none were dismissed or a NEW problem appeared since the dismissal; stay quiet when the current
     * problems are a subset of what was already dismissed.
     */
    fun shouldWarn(current: Set<Problem>, dismissed: Set<Problem>): Boolean = current.isNotEmpty() && !dismissed.containsAll(current)

    /** Health_Status per job/feature for Settings (Requirement 62.6). */
    enum class Status { OK, WARNING, UNKNOWN }

    fun statusOf(state: JobState, now: Instant): Status = when {
        state.lastRunAt == null -> Status.UNKNOWN
        jobMissed(state, now) -> Status.WARNING
        else -> Status.OK
    }
}
