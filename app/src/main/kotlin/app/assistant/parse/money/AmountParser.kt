package app.assistant.parse.money

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Paise

/** Why an amount token was not accepted (Requirement 28.2, 28.5). Carries no text from the message. */
enum class AmountError { MALFORMED, TOO_MANY_DECIMALS, OUT_OF_RANGE }

/**
 * Converts an amount token to exact integer paise (Requirement 28.2). Accepts a Currency_Marker
 * ("Rs", "Rs.", "INR" or the rupee sign, any letter case) before or after the number with or
 * without a space, an optional "/-", comma grouping in Indian (1,23,456) or international
 * (123,456) style or none, and 0, 1 or 2 decimal places. Never uses floating point.
 * Valid_Amount bounds apply: Rs 0.01 to Rs 99,99,99,999.99. A balance may be zero, so callers reading
 * one pass [allowZero].
 */
object AmountParser {
    private const val MARKER = "(?:rs\\.?|inr|\u20B9)"
    private const val NUMBER = "(\\d+|\\d{1,3}(?:,\\d{3})+|\\d{1,2}(?:,\\d{2})+,\\d{3})(?:\\.(\\d+))?"
    private const val SLASH = "(?:/-)?"
    private const val PAISE_PER_RUPEE = 100L

    // A marker is written before or after the number, never both; a bare number is accepted too.
    private val patterns = listOf(
        Regex("^$MARKER\\s*$NUMBER$SLASH$", RegexOption.IGNORE_CASE),
        Regex("^$NUMBER$SLASH\\s*$MARKER$", RegexOption.IGNORE_CASE),
        Regex("^$NUMBER$SLASH$"),
    )

    fun parse(token: String, allowZero: Boolean = false): Outcome<Paise, AmountError> {
        val text = token.trim()
        val match = patterns.firstNotNullOfOrNull { it.matchEntire(text) } ?: return Outcome.Err(AmountError.MALFORMED)
        val whole = match.groupValues[1].replace(",", "")
        val fraction = match.groupValues[2]
        if (fraction.length > 2) return Outcome.Err(AmountError.TOO_MANY_DECIMALS)
        // Valid_Amount has at most 10 whole-rupee digits; reject longer input before it can overflow a Long.
        val rupees = whole.trimStart('0').ifEmpty { "0" }
        if (rupees.length > MAX_RUPEE_DIGITS) return Outcome.Err(AmountError.OUT_OF_RANGE)
        val paise = rupees.toLong() * PAISE_PER_RUPEE + fraction.padEnd(2, '0').toLong()
        val amount = Paise(paise)
        val inRange = if (allowZero) paise in 0..Paise.MAX_VALID.value else Paise.isValid(amount)
        return if (inRange) Outcome.Ok(amount) else Outcome.Err(AmountError.OUT_OF_RANGE)
    }

    private const val MAX_RUPEE_DIGITS = 10
}
