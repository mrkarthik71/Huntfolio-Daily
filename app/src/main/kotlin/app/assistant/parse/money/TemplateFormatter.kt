package app.assistant.parse.money

import app.assistant.domain.model.Paise
import java.time.LocalDate
import java.time.LocalTime

/** How digits are grouped with commas in a rendered amount. */
enum class Grouping { NONE, INDIAN, INTERNATIONAL }

/** How a month is written in a rendered date. */
enum class MonthStyle { NUMERIC, UPPER, LOWER, TITLE }

/**
 * The choices a Bank_Format leaves open when a Parsed_Message is turned back into text (Requirement
 * 28.6): the Currency_Marker form, grouping and decimal count of amounts, how dates and times are
 * written, and how an account is masked. Every choice here is accepted by the matching parser, so
 * property tests can sweep them (design: RenderStyle). Defaults give the plainest text.
 */
data class RenderStyle(
    /** A Currency_Marker such as "Rs", "Rs.", "INR" or the rupee sign in any letter case; empty writes none. */
    val amountMarker: String = "",
    val markerAfter: Boolean = false,
    val markerSpace: Boolean = false,
    val slashSuffix: Boolean = false,
    val grouping: Grouping = Grouping.INDIAN,
    /** Requested decimal places; raised to what the value needs, never above 2. */
    val decimals: Int = 2,
    val monthStyle: MonthStyle = MonthStyle.NUMERIC,
    val fourDigitYear: Boolean = false,
    val padDate: Boolean = true,
    /** Picks among the format's date separators; ignored for a compact date. */
    val separatorIndex: Int = 0,
    val twelveHour: Boolean = false,
    val seconds: Boolean = false,
    val meridiemSpace: Boolean = true,
    val meridiemLowerCase: Boolean = false,
    val maskAccount: Boolean = true,
)

/**
 * Renders a [ParsedMessage] as the text of a [BankFormat] by walking the same template tokens the
 * parser matches, so one definition serves both directions and the round-trip property checks the
 * parser rather than a second hand-written pattern (Requirement 28.6, 28.7, design: Template_Formatter).
 */
object TemplateFormatter {
    private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val PAISE_PER_RUPEE = 100L
    private const val TENTHS = 10L
    private const val TWO_DIGITS = 2
    private const val HALF_DAY = 12
    private const val CENTURY_START = 2000
    private const val CENTURY_END = 2099
    private const val MASK = "XX"

    /**
     * True when [format] has a place for every non-empty field of [message] and a value for every
     * field it has, and the rendered text is something the format itself accepts.
     */
    fun canHold(format: BankFormat, message: ParsedMessage): Boolean {
        val type = format.kind.type
        val fields = format.template.fields.toSet()
        val kindFits = type == message.type && format.kind.reversal == message.isReversal
        return kindFits &&
            FieldName.CODE !in fields &&
            placesMatchValues(fields, message) &&
            format.matcher.matches(render(format, message, RenderStyle()))
    }

    /** A field with no value cannot be written, and a value with no field would be lost. */
    private fun placesMatchValues(fields: Set<FieldName>, m: ParsedMessage): Boolean =
        (m.merchantOrVpa != null) == (FieldName.MERCHANT in fields || FieldName.VPA in fields) &&
            (m.account4 != null) == (FieldName.ACCOUNT4 in fields) &&
            (m.reference != null) == (FieldName.REFERENCE in fields) &&
            (m.balance != null) == (FieldName.BALANCE in fields)

    fun render(format: BankFormat, message: ParsedMessage, style: RenderStyle): String = format.template.tokens.joinToString("") { token ->
        when (token) {
            is TemplateToken.Literal -> token.text
            is TemplateToken.Field -> field(token.name, format, message, style)
        }
    }

    private fun field(name: FieldName, format: BankFormat, m: ParsedMessage, style: RenderStyle): String = when (name) {
        FieldName.AMOUNT -> amount(m.amount, style)
        FieldName.BALANCE -> amount(m.balance ?: Paise(0), style)
        FieldName.DATE, FieldName.DUE_DATE -> date(m.at.toLocalDate(), format, style)
        FieldName.TIME -> time(m.at.toLocalTime(), style)
        FieldName.MERCHANT, FieldName.VPA -> m.merchantOrVpa.orEmpty()
        FieldName.ACCOUNT4 -> (if (style.maskAccount) MASK else "") + m.account4.orEmpty()
        FieldName.REFERENCE -> m.reference.orEmpty()
        FieldName.CODE -> ""
    }

    internal fun amount(paise: Paise, style: RenderStyle): String {
        val rupees = (paise.value / PAISE_PER_RUPEE).toString()
        val cents = (paise.value % PAISE_PER_RUPEE).toString().padStart(TWO_DIGITS, '0')
        val needed = when {
            paise.value % PAISE_PER_RUPEE == 0L -> 0
            paise.value % TENTHS == 0L -> 1
            else -> 2
        }
        val decimals = style.decimals.coerceIn(needed, TWO_DIGITS)
        val number = group(rupees, style.grouping) + (if (decimals == 0) "" else "." + cents.take(decimals)) + (if (style.slashSuffix) "/-" else "")
        val space = if (style.markerSpace) " " else ""
        return when {
            style.amountMarker.isEmpty() -> number
            style.markerAfter -> number + space + style.amountMarker
            else -> style.amountMarker + space + number
        }
    }

    private fun group(digits: String, grouping: Grouping): String = when (grouping) {
        Grouping.NONE -> digits
        Grouping.INTERNATIONAL -> digits.reversed().chunked(3).joinToString(",").reversed()
        Grouping.INDIAN -> if (digits.length <= 3) {
            digits
        } else {
            digits.dropLast(3).reversed().chunked(TWO_DIGITS).joinToString(",").reversed() + "," + digits.takeLast(3)
        }
    }

    internal fun date(d: LocalDate, format: BankFormat, style: RenderStyle): String {
        val separators = format.dateSeparators.toList()
        val separator = if (separators.isEmpty()) "" else separators[style.separatorIndex.mod(separators.size)].toString()
        // A compact date has no separators to show where a field ends, so its fields are fixed width.
        val pad = style.padDate || separator.isEmpty()
        val day = if (pad) d.dayOfMonth.toString().padStart(TWO_DIGITS, '0') else d.dayOfMonth.toString()
        val month = when (style.monthStyle) {
            MonthStyle.NUMERIC -> if (pad) d.monthValue.toString().padStart(TWO_DIGITS, '0') else d.monthValue.toString()
            MonthStyle.UPPER -> MONTH_NAMES[d.monthValue - 1].uppercase()
            MonthStyle.LOWER -> MONTH_NAMES[d.monthValue - 1].lowercase()
            MonthStyle.TITLE -> MONTH_NAMES[d.monthValue - 1]
        }
        // YY is read as 20YY, so only years 2000 to 2099 can be written with two digits.
        val twoDigitYear = !style.fourDigitYear && d.year in CENTURY_START..CENTURY_END
        val year = if (twoDigitYear) (d.year % 100).toString().padStart(TWO_DIGITS, '0') else d.year.toString()
        val parts = when (format.dateOrder) {
            DateOrder.DMY -> listOf(day, month, year)
            DateOrder.MDY -> listOf(month, day, year)
            DateOrder.YMD -> listOf(year, month, day)
        }
        return parts.joinToString(separator)
    }

    internal fun time(t: LocalTime, style: RenderStyle): String {
        val seconds = if (style.seconds) ":" + t.second.toString().padStart(TWO_DIGITS, '0') else ""
        val minute = t.minute.toString().padStart(TWO_DIGITS, '0')
        if (!style.twelveHour) return t.hour.toString().padStart(TWO_DIGITS, '0') + ":" + minute + seconds
        val hour = if (t.hour % HALF_DAY == 0) HALF_DAY else t.hour % HALF_DAY
        val meridiem = (if (t.hour < HALF_DAY) "AM" else "PM").let { if (style.meridiemLowerCase) it.lowercase() else it }
        return hour.toString() + ":" + minute + seconds + (if (style.meridiemSpace) " " else "") + meridiem
    }
}
