package app.assistant.parse.money

import app.assistant.data.money.AssetParserRegistry
import java.io.File

/** The shipped parser registry, loaded from the real asset files, plus test-only formats for shapes the shipped ones do not use. */
object TestRegistries {
    const val BANK_A = "AD-BANKA-S"
    const val BANK_B = "VM-BANKB-T"
    const val UPI_APP = "com.example.upiapp"

    val shipped: ParserRegistry = AssetParserRegistry(AssetParserRegistry.FILES) { File("src/main/assets/parser_registry/$it").inputStream() }

    /** Compact dates, space-separated dates and a YMD bill with dots, none of which the shipped formats cover. */
    val extraFormats: List<BankFormat> = listOf(
        extra("t.compact.debit", "TCOMPACT", "DEBIT", "Rs {amount} debited {date} at {merchant}", "DMY", ""),
        extra("t.space.credit", "TSPACE", "CREDIT", "Credited {amount} on {date}, ref {reference}", "MDY", " "),
        extra("t.ymd.bill", "TYMD", "BILL", "Bill {amount} due {due_date}.", "YMD", "."),
        extra("t.time.debit", "TTIME", "DEBIT", "{amount} paid at {time} to {vpa}", "DMY", "-"),
    )

    /** The shipped registry with [extraFormats] added. */
    val withExtras: ParserRegistry = object : ParserRegistry {
        override val otpKeywords: List<String> = shipped.otpKeywords

        override fun formatsFor(origin: String): List<BankFormat> =
            shipped.formatsFor(origin) + extraFormats.filter { f -> f.origins.any { it.matches(origin) } }
    }

    /** An origin string the registry routes to [format]. */
    fun originFor(format: BankFormat): String = listOf(BANK_A, BANK_B, UPI_APP, "TCOMPACT", "TSPACE", "TYMD", "TTIME")
        .first { origin -> withExtras.formatsFor(origin).any { it.id == format.id } }

    val allFormats: List<BankFormat> =
        listOf(BANK_A, BANK_B, UPI_APP).flatMap { shipped.formatsFor(it) } + extraFormats

    private fun extra(id: String, origin: String, kind: String, template: String, order: String, separators: String): BankFormat =
        BankFormatFactory.create(id, listOf("*$origin*"), kind, template, order, separators, 0)
}
