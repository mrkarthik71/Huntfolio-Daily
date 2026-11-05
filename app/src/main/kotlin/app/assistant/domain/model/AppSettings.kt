package app.assistant.domain.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * User settings for Phase 0 and Phase 1. Later phases add their fields.
 * Defaults are the proposed defaults from the design and need the User's confirmation.
 */
data class AppSettings(
    val lockTimeout: Duration = Duration.ofSeconds(60),
    val morningBrief: LocalTime = LocalTime.of(8, 0),
    val eveningReview: LocalTime = LocalTime.of(21, 0),
    val workingHours: LocalTimeRange = LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)),
    /** Null means Quiet_Hours are off. */
    val quietHours: LocalTimeRange? = LocalTimeRange(LocalTime.of(22, 30), LocalTime.of(7, 0)),
    val dailyCap: Int = 8,
    val perTypeLimit: Map<NotificationType, Int> = defaultPerTypeLimits(),
    val capExemptTypes: Set<NotificationType> = emptySet(),
    /**
     * No Notification_Type is on at first launch, so no permission is requested before the
     * User turns a feature on (Requirements 5.1, 5.8).
     */
    val enabledTypes: Set<NotificationType> = emptySet(),
    val lastDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    val streakRule: StreakRule = StreakRule.ONE_PRIORITY_DONE,
    /** Minimum Free_Gap length a focus-block suggestion needs (Requirement 25.1, 15 to 240 minutes). */
    val minFocusLength: Duration = Duration.ofMinutes(30),
    /** Minutes before a timed Calendar_Event's start at which its reminder is shown (Requirement 24, 0 to 120). */
    val eventLeadTime: Duration = Duration.ofMinutes(15),
    /** AI_Mode. None at first launch, so a fresh install never contacts a provider (Requirement 42.4). */
    val aiMode: AiMode = AiMode.NONE,
    /** AI_Monthly_Cap in paise. 0 means no cap set; Cloud is refused until it is positive (Requirement 45.1). */
    val aiMonthlyCapPaise: Long = 0,
    /** Per-Prompt_Type Cloud model name; a missing entry uses the adapter's default model (Requirement 42.3). */
    val aiModels: Map<PromptType, String> = emptyMap(),
    /** The SMS fallback parser is off by default (Requirement 49.1). */
    val smsFallbackParserEnabled: Boolean = false,
    /** Final importance at or above which an email raises a notification (Requirement 55.1, 0 to 10). */
    val mailImportanceThreshold: Int = 6,
    /** Snippet length limit for mail previews (Requirement 52.6, 80 to 1000 chars). */
    val mailSnippetLength: Int = 300,
    /** When the daily Mail_Digest fires (Requirement 56). */
    val mailDigestTime: LocalTime = LocalTime.of(18, 0),
    /** Mail_Sync_Interval in minutes (Requirement 51.4, 15 to 1440). */
    val mailSyncIntervalMinutes: Int = 60,
) {
    fun limitFor(type: NotificationType): Int = perTypeLimit[type] ?: DEFAULT_PER_TYPE_LIMIT

    companion object {
        const val DEFAULT_PER_TYPE_LIMIT = 3
        const val DEFAULT_EVENT_REMINDER_LIMIT = 8

        fun defaultPerTypeLimits(): Map<NotificationType, Int> = NotificationType.entries.associateWith {
            if (it == NotificationType.EVENT_REMINDER) DEFAULT_EVENT_REMINDER_LIMIT else DEFAULT_PER_TYPE_LIMIT
        }
    }
}
