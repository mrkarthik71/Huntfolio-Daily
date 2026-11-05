package app.assistant.parse.money

import app.assistant.domain.error.Outcome
import app.assistant.domain.error.flatMap
import app.assistant.domain.error.map
import app.assistant.domain.error.mapError
import app.assistant.domain.model.Paise
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Turns the text of one Money_Message into a [ParseResult] (Requirement 28, design: Message_Parser).
 * Pure and total: it never throws, and a message it cannot read becomes `Unknown` with a reason
 * so the caller can pass it to the Review_Queue and carry on (Requirement 28.5). It keeps no
 * message text in its results beyond the extracted fields.
 */
object MessageParser {
    /** Longer inputs go to the Review_Queue without any pattern matching (design: Parser_Registry). */
    const val MAX_INPUT_LENGTH = 2_000

    fun parse(text: String, origin: String, receivedAt: Instant, zone: ZoneId, registry: ParserRegistry): ParseResult {
        val input = text.trim()
        if (input.length > MAX_INPUT_LENGTH) return ParseResult.Unknown(UnknownReason.NO_MATCH)
        val formats = registry.formatsFor(origin)
        // An OTP is recognised before any other format is tried, even when it also holds an amount (Requirement 28.3).
        if (isOtp(input, formats, registry.otpKeywords)) return ParseResult.Otp
        val hit = formats.firstNotNullOfOrNull { format ->
            if (format.kind == FormatKind.OTP) null else format.matcher.matchEntire(input)?.let { format to it }
        } ?: return ParseResult.Unknown(UnknownReason.NO_MATCH)
        return runCatching { interpret(hit.first, hit.second.groupValues, receivedAt, zone) }
            .getOrElse { ParseResult.Unknown(UnknownReason.FIELD_ERROR) }
    }

    private fun isOtp(input: String, formats: List<BankFormat>, keywords: List<String>): Boolean =
        formats.any { it.kind == FormatKind.OTP && it.matcher.matches(input) } || keywords.any { containsWord(input, it) }

    /** Whole-word, case-insensitive: "OTP" is found in "your OTP is 1234" but not in "hotpot". */
    private fun containsWord(input: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        return Regex("(?<![A-Za-z0-9])${Regex.escape(keyword.trim())}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(input)
    }

    private fun interpret(format: BankFormat, groups: List<String>, receivedAt: Instant, zone: ZoneId): ParseResult {
        val type = format.kind.type ?: return ParseResult.FailedPayment(format.id)
        // Group 0 is the whole match; group i + 1 is the i-th field of the template.
        val fields = format.template.fields.zip(groups.drop(1)).toMap()
        return when (val result = extract(format, type, fields, receivedAt, zone)) {
            is Outcome.Ok -> ParseResult.Parsed(result.value, format.id)
            is Outcome.Err -> ParseResult.Unknown(result.error)
        }
    }

    private fun extract(
        format: BankFormat,
        type: MessageType,
        fields: Map<FieldName, String>,
        receivedAt: Instant,
        zone: ZoneId,
    ): Outcome<ParsedMessage, UnknownReason> = amount(fields.getValue(FieldName.AMOUNT), allowZero = false).flatMap { amount ->
        balance(fields[FieldName.BALANCE]).flatMap { balance ->
            timestamp(format, fields, receivedAt, zone).map { at ->
                ParsedMessage(
                    type = type,
                    isReversal = format.kind.reversal,
                    amount = amount,
                    at = at,
                    merchantOrVpa = (fields[FieldName.MERCHANT] ?: fields[FieldName.VPA])?.trim()?.ifEmpty { null },
                    account4 = fields[FieldName.ACCOUNT4]?.takeLast(ACCOUNT_DIGITS),
                    reference = fields[FieldName.REFERENCE],
                    balance = balance,
                )
            }
        }
    }

    private fun amount(token: String, allowZero: Boolean): Outcome<Paise, UnknownReason> =
        AmountParser.parse(token, allowZero).mapError { UnknownReason.INVALID_AMOUNT }

    /** A balance is optional and may be zero. */
    private fun balance(token: String?): Outcome<Paise?, UnknownReason> = when (token) {
        null -> Outcome.Ok(null)
        else -> amount(token, allowZero = true)
    }

    /**
     * A bill carries a due date, which may be in the future; a debit or credit date may be at most
     * 1 day after the Received_Time (Requirement 28.5). A date or time the message lacks comes from
     * the Received_Time (Requirement 28.1).
     */
    private fun timestamp(format: BankFormat, fields: Map<FieldName, String>, receivedAt: Instant, zone: ZoneId): Outcome<LocalDateTime, UnknownReason> {
        val parsed = if (format.kind == FormatKind.BILL) {
            DateTimeParser.parse(fields[FieldName.DUE_DATE], BILL_TIME, format.dateOrder, receivedAt, zone, allowFuture = true)
        } else {
            DateTimeParser.parse(fields[FieldName.DATE], fields[FieldName.TIME], format.dateOrder, receivedAt, zone)
        }
        return parsed.mapError { if (it == DateError.FUTURE_DATE) UnknownReason.FUTURE_DATE else UnknownReason.INVALID_DATE }
    }

    private const val ACCOUNT_DIGITS = 4
    private const val BILL_TIME = "00:00"
}
