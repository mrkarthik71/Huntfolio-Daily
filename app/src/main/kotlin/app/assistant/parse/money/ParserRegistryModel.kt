package app.assistant.parse.money

import app.assistant.domain.model.Paise
import java.time.LocalDateTime

/**
 * Which sender ID or app package a Bank_Format applies to: a case-insensitive pattern where `*`
 * matches any run of characters (so `*BANKA*` matches `AD-BANKA-S`). Sender-list matching with
 * stored prefix and suffix patterns is the SenderMatcher's job (task 19.1), not this class's.
 */
data class OriginPattern(val glob: String) {
    private val regex: Regex = Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    fun matches(origin: String): Boolean = regex.matches(origin)
}

/** The type of a Parsed_Message (Requirement 28.1). An OTP or failed payment produces no Parsed_Message. */
enum class MessageType { DEBIT, CREDIT, BILL }

/**
 * What kind of Money_Message a Bank_Format matches (design: BankFormat.kind). [type] is the
 * Parsed_Message type it yields, or null for OTP and failed-payment formats; a refund or reversal
 * format yields a credit marked [reversal] (Requirement 28.10).
 */
enum class FormatKind(val type: MessageType?, val reversal: Boolean = false) {
    DEBIT(MessageType.DEBIT),
    CREDIT(MessageType.CREDIT),
    BILL(MessageType.BILL),
    OTP(null),
    FAILED(null),
    REVERSAL(MessageType.CREDIT, reversal = true),
}

/** One field a template can hold. [CODE] is an OTP's digits: matched, never stored. */
enum class FieldName(val key: String) {
    AMOUNT("amount"),
    DATE("date"),
    TIME("time"),
    DUE_DATE("due_date"),
    MERCHANT("merchant"),
    VPA("vpa"),
    ACCOUNT4("account4"),
    REFERENCE("reference"),
    BALANCE("balance"),
    CODE("code"),
    ;

    companion object {
        fun fromKey(key: String): FieldName? = entries.firstOrNull { it.key == key }
    }
}

/** One literal or field piece of a Bank_Format's message, in order (design: TemplateToken). */
sealed interface TemplateToken {
    data class Literal(val text: String) : TemplateToken
    data class Field(val name: FieldName) : TemplateToken
}

/** An ordered sequence of tokens; the same sequence both matches and renders (Requirement 28.7). */
data class Template(val tokens: List<TemplateToken>) {
    val fields: List<FieldName> get() = tokens.filterIsInstance<TemplateToken.Field>().map { it.name }
}

/**
 * One parsing rule for one sender and one message template (design: BankFormat).
 * [dateSeparators] are the characters this format writes between day, month and year; empty
 * means a compact date such as `05JAN2025`.
 */
data class BankFormat(
    val id: String,
    val origins: List<OriginPattern>,
    val kind: FormatKind,
    val template: Template,
    val dateOrder: DateOrder,
    val dateSeparators: Set<Char>,
) {
    /** The anchored matcher compiled from [template]; group `i + 1` is the i-th [TemplateToken.Field]. */
    val matcher: Regex by lazy(LazyThreadSafetyMode.PUBLICATION) { TemplateCompiler.compile(this) }
}

/** The fields extracted from a Money_Message (Requirement 28.1). */
data class ParsedMessage(
    val type: MessageType,
    val isReversal: Boolean,
    val amount: Paise,
    val at: LocalDateTime,
    val merchantOrVpa: String?,
    val account4: String?,
    val reference: String?,
    val balance: Paise?,
)

/** Why a Money_Message could not be parsed into a Parsed_Message (design: ParseResult.Unknown). */
enum class UnknownReason { NO_MATCH, INVALID_AMOUNT, INVALID_DATE, FUTURE_DATE, FIELD_ERROR }

/** What the Message_Parser decided for one Money_Message (design: ParseResult). */
sealed interface ParseResult {
    data class Parsed(val message: ParsedMessage, val formatId: String) : ParseResult
    data object Otp : ParseResult
    data class FailedPayment(val formatId: String) : ParseResult
    data class Unknown(val reason: UnknownReason) : ParseResult
}

/** The Bank_Formats and OTP keywords the Message_Parser uses (design: ParserRegistry). */
interface ParserRegistry {
    /** Bank_Formats whose origin matches [origin], OTP formats first (Requirement 28.3). */
    fun formatsFor(origin: String): List<BankFormat>

    val otpKeywords: List<String>
}
