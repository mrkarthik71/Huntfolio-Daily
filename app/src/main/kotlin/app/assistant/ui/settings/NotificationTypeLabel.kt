package app.assistant.ui.settings

import androidx.annotation.StringRes
import app.assistant.R
import app.assistant.domain.model.NotificationType

/**
 * Maps a [NotificationType] to its user-facing label (Requirement 18.6, 18.7).
 * Used by the Why screen and the Notification settings inbox.
 */
@StringRes
fun notificationTypeLabel(type: NotificationType): Int = when (type) {
    NotificationType.MORNING_BRIEF -> R.string.notif_type_morning_brief
    NotificationType.EVENING_REVIEW -> R.string.notif_type_evening_review
    NotificationType.EVENT_REMINDER -> R.string.notif_type_event_reminder
    NotificationType.BILL_DUE -> R.string.notif_type_bill_due
    NotificationType.GOAL_NUDGE -> R.string.notif_type_goal_nudge
    NotificationType.IMPORTANT_MAIL -> R.string.notif_type_important_mail
    NotificationType.MAIL_DIGEST -> R.string.notif_type_mail_digest
    NotificationType.SUBSCRIPTION_RENEWAL -> R.string.notif_type_subscription_renewal
    NotificationType.SUBSCRIPTION_USAGE_CHECK -> R.string.notif_type_subscription_usage_check
    NotificationType.HEALTH_WARNING -> R.string.notif_type_health_warning
    NotificationType.IMPORTANT_DATE -> R.string.notif_type_important_date
    NotificationType.SMART_REMINDER -> R.string.notif_type_smart_reminder
    NotificationType.HABIT_CHECK -> R.string.notif_type_habit_check
    NotificationType.FOLLOW_UP -> R.string.notif_type_follow_up
    NotificationType.BUDGET_ALERT -> R.string.notif_type_budget_alert
    NotificationType.FOCUS_END -> R.string.notif_type_focus_end
    NotificationType.WEEKLY_PLAN -> R.string.notif_type_weekly_plan
    NotificationType.MONEY_DUE -> R.string.notif_type_money_due
}
