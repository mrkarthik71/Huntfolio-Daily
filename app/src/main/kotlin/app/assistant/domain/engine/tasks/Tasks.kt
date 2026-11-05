package app.assistant.domain.engine.tasks

import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.error.fieldErrors
import app.assistant.domain.model.GoalId
import java.time.LocalDate

enum class TaskField { TITLE, ESTIMATE }

data class TaskInput(
    val title: String,
    val dueDate: LocalDate? = null,
    val plannedDate: LocalDate? = null,
    /** Raw minutes text; empty means no estimate. */
    val estimateText: String = "",
    val goalId: GoalId? = null,
)

enum class MoveError { IN_PAST, SAME_DATE, NOT_FOUND }

object TaskValidator {
    const val TITLE_MAX = 200
    val ESTIMATE_RANGE = 1..1_440

    /** Requirements 12.1, 12.2 and 12.6. */
    fun validate(input: TaskInput): FieldErrors<TaskField> = fieldErrors {
        val title = input.title.trim()
        check(TaskField.TITLE, title.isNotEmpty(), ValidationError.EMPTY)
        check(TaskField.TITLE, title.length <= TITLE_MAX, ValidationError.TOO_LONG)
        val est = input.estimateText.trim()
        if (est.isNotEmpty()) {
            val minutes = est.toIntOrNull()
            when {
                minutes == null -> add(TaskField.ESTIMATE, ValidationError.NOT_A_NUMBER)
                minutes !in ESTIMATE_RANGE -> add(TaskField.ESTIMATE, ValidationError.OUT_OF_RANGE)
            }
        }
    }

    fun estimateMinutes(input: TaskInput): Int? = input.estimateText.trim().toIntOrNull()?.takeIf { it in ESTIMATE_RANGE }

    /** Line breaks become spaces, then trim; 1 to 200 characters (Requirements 13.2, 13.4, 13.5). */
    fun normaliseQuickAdd(raw: String): Outcome<String, ValidationError> {
        val text = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()
        return when {
            text.isEmpty() -> Outcome.Err(ValidationError.EMPTY)
            text.length > TITLE_MAX -> Outcome.Err(ValidationError.TOO_LONG)
            else -> Outcome.Ok(text)
        }
    }

    /** Requirement 12.4 and 12.7: a move to a different date that is not in the past. */
    fun checkMove(from: LocalDate?, to: LocalDate, today: LocalDate): Outcome<Unit, MoveError> = when {
        to.isBefore(today) -> Outcome.Err(MoveError.IN_PAST)
        to == from -> Outcome.Err(MoveError.SAME_DATE)
        else -> Outcome.Ok(Unit)
    }
}
