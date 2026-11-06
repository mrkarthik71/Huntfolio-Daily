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

    /** Apex smart reminders (time, place, phone state). */
    SMART_REMINDER,

    /** Habit and medicine check-ins, with a Done button. */
    HABIT_CHECK,

    /** "Waiting for" follow-up nudges. */
    FOLLOW_UP,

    /** A category passing 80% or 100% of its budget. */
    BUDGET_ALERT,

    /** A focus block reaching its end. */
    FOCUS_END,

    /** The weekly planning ritual on the last day of the week. */
    WEEKLY_PLAN,

    /** A SIP, EMI or card payment due tomorrow or today. */
    MONEY_DUE,

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

    /** Ask Apex: a short answer to a question, grounded in facts the app computed. */
    ASSISTANT_ANSWER,

    /** Plan ahead: break each goal into a few concrete steps, sized S, M or L. */
    GOAL_BREAKDOWN,
}

enum class SuggestionResponse { ACCEPTED, DISMISSED, IGNORED }

enum class MailCategory { INTERVIEW, BILL, DEADLINE, DELIVERY, OTHER }

/** Glossary: Streak_Rule. The default counts a day with at least one completed Priority. */
enum class StreakRule { ONE_PRIORITY_DONE, ALL_PRIORITIES_DONE, ANY_TASK_DONE }
