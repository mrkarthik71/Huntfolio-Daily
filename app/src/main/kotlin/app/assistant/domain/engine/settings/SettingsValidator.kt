package app.assistant.domain.engine.settings

import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.AppSettings
import java.time.Duration

enum class SettingsField {
    LOCK_TIMEOUT,
    WORKING_HOURS,
    QUIET_HOURS,
    DAILY_CAP,
    PER_TYPE_LIMIT,
    MIN_FOCUS_LENGTH,
    EVENT_LEAD_TIME,
    AI_MONTHLY_CAP,
    MAIL_IMPORTANCE_THRESHOLD,
    MAIL_SNIPPET_LENGTH,
    MAIL_SYNC_INTERVAL,
}

/**
 * Ranges for every setting. The settings store rejects an invalid update as a whole and keeps
 * the previous values (Requirement 45.6 pattern: invalid value keeps the previous one).
 */
object SettingsValidator {
    val LOCK_TIMEOUT_RANGE: ClosedRange<Duration> = Duration.ZERO..Duration.ofMinutes(30)
    val CAP_RANGE = 1..20
    val MIN_FOCUS_LENGTH_RANGE: ClosedRange<Duration> = Duration.ofMinutes(15)..Duration.ofMinutes(240)
    val EVENT_LEAD_TIME_RANGE: ClosedRange<Duration> = Duration.ZERO..Duration.ofMinutes(120)

    /** 0 means "no cap set" (Cloud stays refused); otherwise a positive paise amount up to ₹100,000/month. */
    val AI_MONTHLY_CAP_RANGE: LongRange = 0L..10_000_000L

    val MAIL_IMPORTANCE_RANGE = 0..10
    val MAIL_SNIPPET_RANGE = 80..1_000
    val MAIL_SYNC_INTERVAL_RANGE = 15..1_440

    fun validate(s: AppSettings): FieldErrors<SettingsField> = fieldErrors {
        check(SettingsField.LOCK_TIMEOUT, s.lockTimeout in LOCK_TIMEOUT_RANGE, ValidationError.OUT_OF_RANGE)
        // Working_Hours is a daytime window: it may not be empty or span midnight.
        check(
            SettingsField.WORKING_HOURS,
            !s.workingHours.isEmpty && !s.workingHours.spansMidnight,
            ValidationError.OUT_OF_RANGE,
        )
        s.quietHours?.let { check(SettingsField.QUIET_HOURS, !it.isEmpty, ValidationError.EMPTY) }
        check(SettingsField.DAILY_CAP, s.dailyCap in CAP_RANGE, ValidationError.OUT_OF_RANGE)
        check(SettingsField.PER_TYPE_LIMIT, s.perTypeLimit.values.all { it in CAP_RANGE }, ValidationError.OUT_OF_RANGE)
        check(SettingsField.MIN_FOCUS_LENGTH, s.minFocusLength in MIN_FOCUS_LENGTH_RANGE, ValidationError.OUT_OF_RANGE)
        check(SettingsField.EVENT_LEAD_TIME, s.eventLeadTime in EVENT_LEAD_TIME_RANGE, ValidationError.OUT_OF_RANGE)
        check(SettingsField.AI_MONTHLY_CAP, s.aiMonthlyCapPaise in AI_MONTHLY_CAP_RANGE, ValidationError.OUT_OF_RANGE)
        check(SettingsField.MAIL_IMPORTANCE_THRESHOLD, s.mailImportanceThreshold in MAIL_IMPORTANCE_RANGE, ValidationError.OUT_OF_RANGE)
        check(SettingsField.MAIL_SNIPPET_LENGTH, s.mailSnippetLength in MAIL_SNIPPET_RANGE, ValidationError.OUT_OF_RANGE)
        check(SettingsField.MAIL_SYNC_INTERVAL, s.mailSyncIntervalMinutes in MAIL_SYNC_INTERVAL_RANGE, ValidationError.OUT_OF_RANGE)
    }
}
