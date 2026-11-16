package app.assistant.domain.model

// Typed IDs, one per entity, so a TaskId can never be passed where a GoalId is expected.
// Room entities keep primitive Long columns; repositories convert at the boundary.

@JvmInline value class TaskId(val value: Long)

@JvmInline value class GoalId(val value: Long)

@JvmInline value class PlanId(val value: Long)

@JvmInline value class TimeBlockId(val value: Long)

@JvmInline value class TransactionId(val value: Long)

@JvmInline value class MerchantId(val value: Long)

/** The built-in Uncategorised category has the fixed id 1, so seeding must insert BuiltinCategories.NAMES in order. */
@JvmInline value class CategoryId(val value: Long) {
    companion object {
        val UNCATEGORISED = CategoryId(1)
    }
}

@JvmInline value class BillId(val value: Long)

@JvmInline value class BudgetId(val value: Long)

@JvmInline value class SubscriptionId(val value: Long)

@JvmInline value class SuggestionId(val value: Long)

@JvmInline value class ReviewItemId(val value: Long)

@JvmInline value class InboxItemId(val value: Long)

@JvmInline value class CalendarEventId(val value: Long)

@JvmInline value class NotificationLogId(val value: Long)

@JvmInline value class AuditId(val value: Long)

@JvmInline value class ReportId(val value: Long)

@JvmInline value class MilestoneId(val value: Long)

@JvmInline value class RoutineId(val value: Long)

@JvmInline value class ReminderId(val value: Long)

@JvmInline value class CardId(val value: Long)
