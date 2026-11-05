package app.assistant.parse.money

import app.assistant.data.money.AssetParserRegistry
import app.assistant.data.money.ParserRegistryJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Task 18.5: the Parser_Registry loads, compiles and keeps its formats distinguishable. */
class ParserRegistryTest {

    private val registry = AssetParserRegistry(AssetParserRegistry.FILES) { File("src/main/assets/parser_registry/$it").inputStream() }
    private val everyFormat: List<BankFormat> =
        listOf("AD-BANKA-S", "VM-BANKB-T", "com.example.upiapp").flatMap { registry.formatsFor(it) }

    /** One synthetic message per shipped format, with the captured fields it must yield. */
    private val samples: Map<String, Pair<String, List<String>>> = mapOf(
        "bankA.debit.v1" to (
            "Rs 1,234.50 debited from a/c XX1234 on 05-01-25 14:05 at Cafe Blue. Avl Bal Rs 20,000.00" to
                listOf("1,234.50", "XX1234", "05-01-25", "14:05", "Cafe Blue", "20,000.00")
            ),
        "bankA.credit.v1" to (
            "Rs 500 credited to a/c XX1234 on 05-Jan-25 02:05 PM. Ref AB12CD34. Avl Bal Rs 20,500.00" to
                listOf("500", "XX1234", "05-Jan-25", "02:05 PM", "AB12CD34", "20,500.00")
            ),
        "bankA.bill.v1" to (
            "Your BankA Credit Card bill of Rs 3,456.00 is due on 20-01-25. Pay in the BankA app." to
                listOf("3,456.00", "20-01-25")
            ),
        "bankA.otp.v1" to (
            "123456 is your OTP for a transaction of Rs 250.5 at Shop One. Valid for 10 mins." to
                listOf("123456", "250.5", "Shop One")
            ),
        "bankA.failed.v1" to (
            "Your transaction of Rs 99 at Shop One on 05-01-25 has failed/declined. Ref ZX90QW12." to
                listOf("99", "Shop One", "05-01-25", "ZX90QW12")
            ),
        "bankA.reversal.v1" to (
            "Rs 99 has been reversed/refunded to your a/c XX1234 on 05-01-25. Ref ZX90QW12." to
                listOf("99", "XX1234", "05-01-25", "ZX90QW12")
            ),
        "bankB.debit.v1" to (
            "INR 1,23,456.78 spent on your BankB Card XX4321 at Store Two on 05/01/2025. Call 1800-xxx if not you." to
                listOf("1,23,456.78", "4321", "Store Two", "05/01/2025")
            ),
        "bankB.credit.v1" to (
            "INR 250.00 credited to a/c XX4321 on 01/05/2025 09:30 by Friend Name. Info: UTR998877" to
                listOf("250.00", "4321", "01/05/2025", "09:30", "Friend Name", "UTR998877")
            ),
        "bankB.bill.v1" to (
            "BankB: Your card ending 4321 has a bill of INR 5,000 due by 01/20/2025." to
                listOf("4321", "5,000", "01/20/2025")
            ),
        "bankB.reversal.v1" to (
            "Refund of Rs 99 for Store Two credited to a/c XX4321 on 05JAN2025. Ref RF778899" to
                listOf("Rs 99", "Store Two", "4321", "05JAN2025", "RF778899")
            ),
        "upiapp.debit.v1" to (
            "Paid Rs250 to shop@upi on 2025-01-05 at 14:05. UPI Ref 1234567890." to
                listOf("Rs250", "shop@upi", "2025-01-05", "14:05", "1234567890")
            ),
        "upiapp.credit.v1" to (
            "Received Rs250.50 from friend.name@bank on 2025-01-05 at 14:05. UPI Ref 1234567890." to
                listOf("Rs250.50", "friend.name@bank", "2025-01-05", "14:05", "1234567890")
            ),
        "upiapp.failed.v1" to (
            "Payment of Rs250 to shop@upi failed. UPI Ref 1234567890." to
                listOf("Rs250", "shop@upi", "1234567890")
            ),
    )

    @Test
    fun `every shipped format has a sample, and every sample matches exactly its own format`() {
        assertEquals(samples.keys, everyFormat.map { it.id }.toSet(), "formats and samples must correspond one to one")
        samples.forEach { (id, sample) ->
            val (text, _) = sample
            val matching = everyFormat.filter { it.matcher.matches(text) }.map { it.id }
            assertEquals(listOf(id), matching, "sample for $id")
        }
    }

    @Test
    fun `fields are captured in template order`() {
        samples.forEach { (id, sample) ->
            val (text, expected) = sample
            val format = everyFormat.first { it.id == id }
            val groups = format.matcher.matchEntire(text)!!.groupValues.drop(1)
            assertEquals(expected, groups, id)
            assertEquals(format.template.fields.size, groups.size, id)
        }
    }

    @Test
    fun `all six kinds are covered across the shipped formats`() {
        assertEquals(FormatKind.entries.toSet(), everyFormat.map { it.kind }.toSet())
    }

    @Test
    fun `OTP formats come first for an origin, and the keyword list is loaded`() {
        val forBankA = registry.formatsFor("AD-BANKA-S")
        assertEquals(FormatKind.OTP, forBankA.first().kind)
        assertTrue(forBankA.drop(1).none { it.kind == FormatKind.OTP })
        assertEquals(listOf("OTP", "one time password", "verification code"), registry.otpKeywords)
    }

    @Test
    fun `origins match by case-insensitive glob and unknown senders get no formats`() {
        assertTrue(registry.formatsFor("ad-banka-s").isNotEmpty())
        assertTrue(registry.formatsFor("BANKA").isNotEmpty())
        assertTrue(registry.formatsFor("com.example.upiapp").all { it.id.startsWith("upiapp.") })
        assertTrue(registry.formatsFor("UNKNOWN-SENDER").isEmpty())
        assertTrue(OriginPattern("*BANKA*").matches("AD-BANKA-S"))
        assertTrue(!OriginPattern("BANKA").matches("AD-BANKA-S"))
    }

    @Test
    fun `a malformed registry file is rejected with the format id and no message text`() {
        fun load(template: String, kind: String = "DEBIT", extra: String = "") = ParserRegistryJson.parse(
            """{"formats":[{"id":"t.v1","origins":["*T*"],"kind":"$kind","template":"$template","date_order":"DMY" $extra}]}""",
        )
        assertTrue(load("Rs {amount} on {foo}").isFailure, "unknown field")
        assertTrue(load("Rs {amount}{date}").isFailure, "adjacent fields")
        assertTrue(load("Rs {amount} and {amount}").isFailure, "repeated field")
        assertTrue(load("debited on {date}").isFailure, "debit without amount")
        assertTrue(load("due {amount}", kind = "BILL").isFailure, "bill without due_date")
        assertTrue(load("on {due_date} Rs {amount}").isFailure, "due_date outside BILL")
        assertTrue(load("Rs {amount}", kind = "SPENT").isFailure, "unknown kind")
        assertTrue(load("Rs {amount}", extra = ""","date_separators":"a"""").isFailure, "alphanumeric separator")
        assertTrue(ParserRegistryJson.parse("not json").isFailure)
        assertTrue(load("Rs {amount} debited").isSuccess)
        assertTrue(load("Rs {amount} debited").exceptionOrNull() == null)
        assertTrue(load("Rs {amount}{date}").exceptionOrNull()!!.message!!.contains("t.v1"))
    }

    @Test
    fun `long adversarial input cannot make matching run away`() {
        val inputs = listOf(
            "Rs " + "9".repeat(1_990),
            "X".repeat(2_000),
            "Rs 1 ".repeat(400),
            "Rs 1,".repeat(400),
            "a/c " + "XX".repeat(900) + "1",
            "Rs 5 debited from a/c XX1234 on " + "1-".repeat(900),
            "Paid Rs250 to " + "a.".repeat(900) + "@",
        )
        val started = System.nanoTime()
        inputs.forEach { text -> everyFormat.forEach { it.matcher.matches(text) } }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 3_000, "matching 7 adversarial inputs against ${everyFormat.size} formats took $millis ms")
    }
}
