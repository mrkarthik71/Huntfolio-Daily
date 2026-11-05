package app.assistant.parse.money

/**
 * Builds the single regular expression a Bank_Format both matches and renders with (design:
 * Parser_Registry). Every field pattern is bounded and uses possessive quantifiers or a bounded
 * lazy run, so matching a long input cannot backtrack catastrophically (Requirement 28 design note).
 * Group `i + 1` of the result is the i-th [TemplateToken.Field] of the template.
 */
object TemplateCompiler {
    private const val MARKER = "(?:rs\\.?|inr|\u20B9)"
    private const val AMOUNT = "(?:$MARKER ?)?+[0-9]++(?:,[0-9]++)*+(?:\\.[0-9]++)?+(?:/-)?+(?: ?$MARKER)?+"
    private const val TIME = "[0-9]{1,2}+:[0-9]{2}+(?::[0-9]{2}+)?+(?: ?[AaPp][Mm])?+"
    private const val ACCOUNT4 = "(?:[Xx*]{1,12}+)?+[0-9]{4}+"
    private const val REFERENCE = "[A-Za-z0-9]{4,24}+"
    private const val CODE = "[0-9]{4,8}+"
    private const val VPA = "[A-Za-z0-9._\\-]{1,40}+@[A-Za-z0-9\\-]{1,30}+(?:\\.[A-Za-z0-9\\-]{1,30}+)*+"
    private const val MERCHANT = "[^\\r\\n]{1,60}?"
    private const val COMPACT_DATE = "[0-9A-Za-z]{6,9}+"

    private val PLACEHOLDER = Regex("\\{([a-z0-9_]+)}")

    fun compile(format: BankFormat): Regex {
        val body = format.template.tokens.joinToString("") { token ->
            when (token) {
                is TemplateToken.Literal -> Regex.escape(token.text)
                is TemplateToken.Field -> "(${fieldSource(token.name, format.dateSeparators)})"
            }
        }
        return Regex("\\A$body\\z", RegexOption.IGNORE_CASE)
    }

    /** The regex source for one field. Contains no capturing group, so group numbers follow the fields. */
    internal fun fieldSource(name: FieldName, dateSeparators: Set<Char>): String = when (name) {
        FieldName.AMOUNT, FieldName.BALANCE -> AMOUNT
        FieldName.DATE, FieldName.DUE_DATE -> dateSource(dateSeparators)
        FieldName.TIME -> TIME
        FieldName.MERCHANT -> MERCHANT
        FieldName.VPA -> VPA
        FieldName.ACCOUNT4 -> ACCOUNT4
        FieldName.REFERENCE -> REFERENCE
        FieldName.CODE -> CODE
    }

    private fun dateSource(separators: Set<Char>): String {
        if (separators.isEmpty()) return COMPACT_DATE
        val klass = separators.joinToString("") { "\\" + it }
        return "[0-9A-Za-z]{1,4}+(?:[$klass][0-9A-Za-z]{1,4}+){2}+"
    }

    /**
     * Splits "Rs {amount} debited on {date}" into literal and field tokens. Returns the unknown
     * placeholder name as the error so the loader can report it without any message text.
     */
    fun parseTemplate(text: String): Result<Template> {
        val tokens = ArrayList<TemplateToken>()
        var last = 0
        for (m in PLACEHOLDER.findAll(text)) {
            if (m.range.first > last) tokens += TemplateToken.Literal(text.substring(last, m.range.first))
            val field = FieldName.fromKey(m.groupValues[1])
                ?: return Result.failure(IllegalArgumentException("unknown field ${m.groupValues[1]}"))
            tokens += TemplateToken.Field(field)
            last = m.range.last + 1
        }
        if (last < text.length) tokens += TemplateToken.Literal(text.substring(last))
        return Result.success(Template(tokens))
    }
}
