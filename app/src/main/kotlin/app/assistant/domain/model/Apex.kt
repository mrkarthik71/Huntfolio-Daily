package app.assistant.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The kind of attention a block of work needs; the scheduler matches it to the User's energy windows. */
enum class EnergyTag { DEEP_WORK, LIGHT_ADMIN, PHYSICAL_ROUTINE, REST }

/** How a finished Task is proven: nothing, a photo kept encrypted on the phone, a link, or tracked time. */
enum class VerifyKind { NONE, PHOTO, LINK, TIMER }

/** A checkpoint inside a Goal: Goal → Milestones → atomic Tasks. */
data class Milestone(
    val id: MilestoneId,
    val goalId: GoalId,
    val title: String,
    val position: Int,
    val dueDate: LocalDate?,
    val estimateMinutes: Int?,
    val completedAt: Instant?,
) {
    val done: Boolean get() = completedAt != null
}

/** A repeating block (gym, reading) on chosen weekdays; the rescheduler may slide it by [flexibleMinutes]. */
data class Routine(
    val id: RoutineId,
    val title: String,
    val energy: EnergyTag,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val durationMinutes: Int,
    val flexibleMinutes: Int,
    val active: Boolean,
)

enum class ReminderKind { TIME, PLACE, DEVICE }

/** Phone states a reminder can wait for. */
enum class DeviceTrigger { CHARGING, CAR_BLUETOOTH, HEADPHONES }

/**
 * A contextual reminder. [kind] says which trigger fields are set: [fireAt] for TIME; [placeName],
 * [latitude], [longitude], [radiusMeters] and [onEnter] for PLACE (kept on the phone only); [device]
 * and optional [notBefore] for DEVICE.
 */
data class SmartReminder(
    val id: ReminderId,
    val message: String,
    val kind: ReminderKind,
    val fireAt: Instant? = null,
    val placeName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    val onEnter: Boolean = true,
    val device: DeviceTrigger? = null,
    val notBefore: LocalTime? = null,
    val active: Boolean = true,
    val taskId: TaskId? = null,
)

enum class CardKind { OVERLOAD, GOAL_AT_RISK, RENEWAL_SOON, FREE_SLOT, ROUTINE_MISSED }

enum class CardStatus { NEW, ACCEPTED, DISMISSED }

/** A proactive heads-up for a day, with the reason shown under "Why?". Nothing changes until it is accepted. */
data class ProactiveCard(
    val id: CardId,
    val kind: CardKind,
    val forDate: LocalDate,
    val title: String,
    val reason: String,
    val status: CardStatus,
)
