package app.assistant.parse.money

import app.assistant.domain.error.Outcome
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The order of day, month and year in a Bank_Format's date (Requirement 28.9). */
enum class DateOrder { DMY, MDY, YMD }

/** Why a date or time was not accepted. Carries no text from the message. */
enum class DateError { INVALID_DATE, INVALID_TIME, FUTURE_DATE }

/**
 * Reads the date and time of a Money_Message (Requirement 28.9). The Bank_Format fixes the
 * day-month-year [DateOrder]; the separator may be "-", "/", "." or a space, or absent when the
 * date is compact ("05JAN2025", "050125"). Months are numeric or 3-letter English names in any
 * case, a 2-digit year YY is 20YY, and times are 24-hour or 12-hour with AM or PM. A missing date
 * or time of day comes from [receivedAt] in [zone]. Pure: no clock access.
 */
object DateTimeParser {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .withIndex().associate { (i, name) -> name to i + 1 }

    private val SEPARATED = Regex("^([0-9A-Za-z]{1,4})([-/. ])([0-9A-Za-z]{1,4})\\2([0-9A-Za-z]{1,4})$")
    private val COMPACT_NUMERIC = Regex("^(?:\\d{6}|\\d{8})$")
    private val COMPACT_ALPHA = mapOf(
        DateOrder.DMY to Regex("^(\\d{1,2})([A-Za-z]{3})(\\d{2}|\\d{4})$"),
        DateOrder.MDY to Regex("^([A-Za-z]{3})(\\d{1,2})(\\d{2}|\\d{4})$"),
        DateOrder.YMD to Regex("^(\\d{2}|\\d{4})([A-Za-z]{3})(\\d{1,2})$"),
    )
    private val TIME = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp][Mm])?$")
    private const val HALF_DAY = 12
    private const val TWO_DIGITS = 2
    private const val FOUR_DIGITS = 4
    private const val CENTURY = 2000

    /**
     * [allowFuture] is false for debit and credit messages, whose date may be at most 1 day after
     * [receivedAt] (Requirement 28.5); a bill's due date passes true.
     */
    fun parse(
        date: String?,
        time: String?,
        order: DateOrder,
        receivedAt: Instant,
        zone: ZoneId,
        allowFuture: Boolean = false,
    ): Outcome<LocalDateTime, DateError> {
        val received = receivedAt.atZone(zone).toLocalDateTime()
        val day = if (date == null) received.toLocalDate() else parseDate(date.trim(), order)
        if (day == null) return Outcome.Err(DateError.INVALID_DATE)
        val clock = if (time == null) received.toLocalTime() else parseTime(time.trim())
        if (clock == null) return Outcome.Err(DateError.INVALID_TIME)
        if (!allowFuture && day.isAfter(received.toLocalDate().plusDays(1))) return Outcome.Err(DateError.FUTURE_DATE)
        return Outcome.Ok(LocalDateTime.of(day, clock))
    }

    private fun parseDate(text: String, order: DateOrder): LocalDate? {
        val (first, second, third) = split(text, order) ?: return null
        val (dayToken, monthToken, yearToken) = when (order) {
            DateOrder.DMY -> Triple(first, second, third)
            DateOrder.MDY -> Triple(second, first, third)
            DateOrder.YMD -> Triple(third, second, first)
        }
        val d = number(dayToken, 1..TWO_DIGITS)
        val m = month(monthToken)
        val y = year(yearToken)
        if (d == null || m == null || y == null) return null
        return try {
            LocalDate.of(y, m, d)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** The three date fields in the order they were written. */
    private fun split(text: String, order: DateOrder): Triple<String, String, String>? {
        SEPARATED.matchEntire(text)?.let { return Triple(it.groupValues[1], it.groupValues[3], it.groupValues[4]) }
        if (COMPACT_NUMERIC.matches(text)) {
            val yearFirst = order == DateOrder.YMD
            val yearLength = text.length - 2 * TWO_DIGITS
            return if (yearFirst) {
                Triple(text.take(yearLength), text.substring(yearLength, yearLength + TWO_DIGITS), text.takeLast(TWO_DIGITS))
            } else {
                Triple(text.take(TWO_DIGITS), text.substring(TWO_DIGITS, 2 * TWO_DIGITS), text.takeLast(yearLength))
            }
        }
        val m = COMPACT_ALPHA.getValue(order).matchEntire(text) ?: return null
        return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    private fun number(token: String, length: IntRange): Int? = token.takeIf { it.length in length && it.all(Char::isDigit) }?.toInt()

    private fun month(token: String): Int? = when {
        token.length == 3 && token.all(Char::isLetter) -> MONTHS[token.lowercase()]
        else -> number(token, 1..TWO_DIGITS)?.takeIf { it in 1..MONTHS.size }
    }

    /** A 2-digit year YY is 20YY; a 4-digit year is taken as written. */
    private fun year(token: String): Int? = when (token.length) {
        TWO_DIGITS -> number(token, TWO_DIGITS..TWO_DIGITS)?.plus(CENTURY)
        FOUR_DIGITS -> number(token, FOUR_DIGITS..FOUR_DIGITS)
        else -> null
    }

    private fun parseTime(text: String): LocalTime? {
        val m = TIME.matchEntire(text) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val second = m.groupValues[3].ifEmpty { "0" }.toInt()
        val meridiem = m.groupValues[4].lowercase()
        val hour24 = when (meridiem) {
            "" -> hour.takeIf { it in 0..HALF_DAY * 2 - 1 }
            else -> hour.takeIf { it in 1..HALF_DAY }?.let { (it % HALF_DAY) + if (meridiem == "pm") HALF_DAY else 0 }
        }
        return try {
            hour24?.let { LocalTime.of(it, minute, second) }
        } catch (_: DateTimeException) {
            null
        }
    }
}
