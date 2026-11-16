package app.assistant.ai.output

import app.assistant.domain.model.MailCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The output schema for each Prompt_Type (Requirement 47.1, 47.2, design: Output schemas). Each is
 * a `@Serializable` data class decoded by [strictJson], which rejects unknown keys and anything that
 * is not a single JSON object. Field-level limits (lengths, counts, ranges, membership) are checked
 * by the Output_Validator after decoding, not by the serializer.
 */

/** A proposed change the user can accept or dismiss; at most [MAX_SUGGESTIONS] per output. */
@Serializable
data class SuggestionDto(val text: String, val reason: String)

/** A plan line: an opaque task handle placed in a time window, both from the payload. */
@Serializable
data class PlacementDto(@SerialName("task") val taskHandle: String, @SerialName("start") val startMinute: Int, @SerialName("end") val endMinute: Int)

@Serializable
data class DailyPlanOutput(val summary: String, val placements: List<PlacementDto> = emptyList(), val suggestions: List<SuggestionDto> = emptyList())

@Serializable
data class EveningReviewOutput(val summary: String, val encouragement: String = "", val suggestions: List<SuggestionDto> = emptyList())

@Serializable
data class WeeklyReviewOutput(val summary: String, val suggestions: List<SuggestionDto> = emptyList())

@Serializable
data class MonthlyMoneyReviewOutput(val summary: String, val suggestions: List<SuggestionDto> = emptyList())

@Serializable
data class AssistantAnswerOutput(val summary: String, val suggestions: List<SuggestionDto> = emptyList())

@Serializable
data class MailClassifierOutput(
    @SerialName("item") val itemHandle: String,
    val score: Int,
    val category: String,
    @SerialName("one_line") val oneLine: String = "",
)

@Serializable
data class SmsFallbackOutput(
    val type: String,
    @SerialName("amount") val amountText: String,
    @SerialName("merchant") val merchant: String = "",
    @SerialName("date") val dateText: String = "",
)

/** Shared numeric limits used by both the schemas and the Output_Validator. */
/** One step of a goal from the breakdown prompt; [goal] is the goal's handle (g1, g2…). */
@Serializable
data class GoalStepDto(val goal: String, val title: String, val size: String)

@Serializable
data class GoalBreakdownOutput(val summary: String = "", val steps: List<GoalStepDto>)

object OutputLimits {
    const val MAX_STEPS = 40
    val STEP_SIZES: Set<String> = setOf("S", "M", "L")

    const val MAX_SUGGESTIONS = 3
    const val MAX_SUMMARY = 600
    const val MAX_SHORT = 200
    const val MAX_ONE_LINE = 120
    const val MAIL_SCORE_MIN = 0
    const val MAIL_SCORE_MAX = 10

    val MAIL_CATEGORIES: Set<String> = MailCategory.entries.map { it.name }.toSet()
    val SMS_TYPES: Set<String> = setOf("DEBIT", "CREDIT", "BILL")
}

/**
 * A single strict Json for every output type. `ignoreUnknownKeys = false` makes an unexpected field
 * a decode failure (Requirement 47.5), and `isLenient = false` forbids the sloppy JSON some models
 * emit. The serializer parses exactly one object; array or scalar roots fail.
 */
val strictJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    explicitNulls = false
}
