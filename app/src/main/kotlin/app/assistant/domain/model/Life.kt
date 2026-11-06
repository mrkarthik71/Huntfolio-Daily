package app.assistant.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// The "whole day" features (Room v12): habits and medicine, important dates and documents,
// savings goals, follow-ups, the evening mood check and focus sessions. All typed by the User.

enum class HabitKind { HABIT, MEDICINE }

/** Something to do at set times on set days. A [HabitKind.MEDICINE] habit is a dose schedule. */
data class Habit(
    val id: Long,
    val name: String,
    val kind: HabitKind,
    val times: List<LocalTime>,
    val days: Set<DayOfWeek>,
    val active: Boolean,
    val createdOn: LocalDate,
)

/** One slot of a habit marked done. Slots not stored are not done. */
data class HabitCheck(val habitId: Long, val date: LocalDate, val slot: LocalTime)

enum class DateKind { BIRTHDAY, ANNIVERSARY, DOCUMENT, INSURANCE, WARRANTY, OTHER }

/**
 * A date to remember. Birthdays and anniversaries repeat every year; documents, insurance and
 * warranties are one expiry date (renewing is a new date).
 */
data class ImportantDate(val id: Long, val title: String, val kind: DateKind, val date: LocalDate, val yearly: Boolean, val fromContacts: Boolean = false)

data class SavingsGoal(val id: Long, val name: String, val target: Paise, val deadline: LocalDate?, val createdOn: LocalDate)

data class SavingsEntry(val id: Long, val goalId: Long, val amount: Paise, val date: LocalDate)

/** "Waiting for [person] about [about]": nudge me on [nudgeOn] if it is still open. */
data class FollowUp(val id: Long, val person: String, val about: String, val nudgeOn: LocalDate, val done: Boolean, val createdOn: LocalDate)

/** The evening check: mood 1 (low) to 5 (great), energy 1 (drained) to 3 (charged). */
data class MoodEntry(val date: LocalDate, val mood: Int, val energy: Int) {
    companion object {
        val MOOD_RANGE = 1..5
        val ENERGY_RANGE = 1..3
    }
}

/** A timed focus block. [endedAt] is null while it runs. */
data class FocusSession(
    val id: Long,
    val title: String,
    val taskId: Long?,
    val startedAt: Instant,
    val plannedMinutes: Int,
    val endedAt: Instant?,
    val completed: Boolean,
)
