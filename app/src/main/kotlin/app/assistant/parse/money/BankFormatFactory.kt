package app.assistant.parse.money

/** One loaded registry file: its OTP keywords and the Bank_Formats it defines. */
data class RegistryFile(val otpKeywords: List<String>, val formats: List<BankFormat>)

/**
 * Builds a [BankFormat] from the raw strings of a registry entry and enforces the rules a template
 * must meet so one definition parses and renders unambiguously. Pure Kotlin: the JSON reading that
 * produces these strings lives in the data layer. Errors name formats and fields only, never
 * message text.
 */
object BankFormatFactory {

    @Suppress("LongParameterList")
    fun create(id: String?, origins: List<String>?, kind: String?, template: String?, dateOrder: String?, dateSeparators: String?, index: Int): BankFormat {
        val formatId = id.orEmpty()
        val label = formatId.ifBlank { "#$index" }
        require(formatId.isNotBlank()) { "format $label: needs an id" }
        val parsedTemplate = TemplateCompiler.parseTemplate(template.orEmpty())
            .getOrElse { throw IllegalArgumentException("format $label: ${it.message}") }
        val parsedKind = runCatching { FormatKind.valueOf(kind.orEmpty().uppercase()) }
            .getOrElse { throw IllegalArgumentException("format $label: unknown kind '$kind'") }
        val parsedOrder = runCatching { DateOrder.valueOf(dateOrder.orEmpty().uppercase()) }
            .getOrElse { throw IllegalArgumentException("format $label: unknown date_order '$dateOrder'") }
        val originList = origins.orEmpty()
        val separators = dateSeparators.orEmpty()
        require(originList.isNotEmpty()) { "format $label: needs at least one origin" }
        require(separators.none { it.isLetterOrDigit() }) { "format $label: date_separators must be punctuation or space" }
        validate(label, parsedKind, parsedTemplate)
        return BankFormat(
            id = formatId,
            origins = originList.map(::OriginPattern),
            kind = parsedKind,
            template = parsedTemplate,
            dateOrder = parsedOrder,
            dateSeparators = separators.toSet(),
        )
    }

    private fun validate(label: String, kind: FormatKind, template: Template) {
        val fields = template.fields
        require(fields.size == fields.toSet().size) { "format $label: a field appears more than once" }
        // Two fields with no literal between them cannot be split apart again when parsing.
        val adjacent = template.tokens.zipWithNext().any { (a, b) -> a is TemplateToken.Field && b is TemplateToken.Field }
        require(!adjacent) { "format $label: two fields need literal text between them" }
        require(!(FieldName.MERCHANT in fields && FieldName.VPA in fields)) { "format $label: use {merchant} or {vpa}, not both" }
        if (kind != FormatKind.OTP && kind != FormatKind.FAILED) {
            require(FieldName.AMOUNT in fields) { "format $label: needs {amount}" }
        }
        require((FieldName.DUE_DATE in fields) == (kind == FormatKind.BILL)) {
            "format $label: {due_date} is for BILL formats only, and BILL needs it"
        }
    }
}
