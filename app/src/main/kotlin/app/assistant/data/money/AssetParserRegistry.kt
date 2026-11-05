package app.assistant.data.money

import app.assistant.parse.money.BankFormat
import app.assistant.parse.money.FormatKind
import app.assistant.parse.money.ParserRegistry
import java.io.InputStream

/**
 * [ParserRegistry] backed by every JSON file read through [open] (one per bank or app, in the
 * assets parser_registry folder). All formats across all files are pooled; OTP formats are
 * returned first for any origin so an OTP is classified before any other Bank_Format is tried
 * (Requirement 28.3). Built once at startup; the files are static app data, not user data.
 */
class AssetParserRegistry(fileNames: List<String>, open: (String) -> InputStream) : ParserRegistry {
    private val allFormats: List<BankFormat>
    override val otpKeywords: List<String>

    init {
        val formats = ArrayList<BankFormat>()
        val keywords = ArrayList<String>()
        for (name in fileNames) {
            val text = open(name).bufferedReader().use { it.readText() }
            val file = ParserRegistryJson.parse(text).getOrElse { throw IllegalStateException("parser_registry/$name: ${it.message}", it) }
            formats += file.formats
            keywords += file.otpKeywords
        }
        allFormats = formats
        val ids = formats.map { it.id }
        check(ids.size == ids.toSet().size) { "parser_registry: duplicate format id" }
        otpKeywords = keywords.distinct()
    }

    companion object {
        /** The registry files shipped in `assets/parser_registry/`, in load order. */
        val FILES = listOf("common.json", "bank_a.json", "bank_b.json", "upi_app.json")
    }

    override fun formatsFor(origin: String): List<BankFormat> {
        val matching = allFormats.filter { format -> format.origins.any { it.matches(origin) } }
        val (otp, rest) = matching.partition { it.kind == FormatKind.OTP }
        return otp + rest
    }
}
