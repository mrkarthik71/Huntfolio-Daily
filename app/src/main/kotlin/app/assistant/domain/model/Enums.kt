package app.assistant.domain.model

import java.time.LocalDate

enum class GoalPeriod { YEAR, MONTH, WEEK }

/** One occurrence of a Goal_Period, from [first] to [last] inclusive. */
data class PeriodInstance(val period: GoalPeriod, val first: LocalDate, val last: LocalDate) {
    init {
        require(!last.isBefore(first)) { "PeriodInstance last is before first" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(first) && !date.isAfter(last)

    val totalDays: Long get() = last.toEpochDay() - first.toEpochDay() + 1
}

enum class TaskStatus { OPEN, DONE, DROPPED }

enum class TxnType { DEBIT, CREDIT }

/** Where a Transaction came from. MANUAL Transactions are never merged automatically. */
enum class SourceKind { SMS, APP, MANUAL }

enum class SubscriptionInterval(val perYear: Int) {
    WEEKLY(52),
    MONTHLY(12),
    QUARTERLY(4),
    YEARLY(1),
}

enum class SubscriptionStatus { PROPOSED, ACTIVE, REJECTED, ENDED }

enum class BillRecurrence { NONE, MONTHLY, YEARLY }

enum class NotificationType {
    MORNING_BRIEF,
    EVENING_REVIEW,
    EVENT_REMINDER,
    BILL_DUE,
    GOAL_NUDGE,
    IMPORTANT_MAIL,
    MAIL_DIGEST,
    SUBSCRIPTION_RENEWAL,
    SUBSCRIPTION_USAGE_CHECK,
    HEALTH_WARNING,

    /** Only where important dates are enabled (Requirement 68). */
    IMPORTANT_DATE,
}

enum class AiMode { CLOUD, ON_DEVICE, NONE }

enum class PromptType {
    DAILY_PLAN,
    EVENING_REVIEW,
    WEEKLY_REVIEW,
    MONTHLY_MONEY_REVIEW,
    MAIL_CLASSIFIER,
    SMS_FALLBACK_PARSER,
}

enum class SuggestionResponse { ACCEPTED, DISMISSED, IGNORED }

enum class MailCategory { INTERVIEW, BILL, DEADLINE, DELIVERY, OTHER }

/** Glossary: Streak_Rule. The default counts a day with at least one completed Priority. */
enum class StreakRule { ONE_PRIORITY_DONE, ALL_PRIORITIES_DONE, ANY_TASK_DONE }
