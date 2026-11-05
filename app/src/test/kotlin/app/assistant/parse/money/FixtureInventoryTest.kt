package app.assistant.parse.money

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 18.8: fails if a shipped Bank_Format has fewer fixtures than Requirement 28.8 needs, if a
 * message source Phase 3 reads has no Prompt_Injection_Fixture (Requirement 4.5), or if a fixture
 * holds a name, account digit, VPA, phone or reference number not in the synthetic-value lists
 * (design: FixtureInventoryTest; full 200/5-per-format/10-OTP/10-injection count is Phase 7, Req 64.1).
 */
class FixtureInventoryTest {

    private val fixtures = MoneyFixtures.all()
    private val synthetic = MoneyFixtures.synthetic()
    private val shippedFormatIds = TestRegistries.allFormats.filterNot { it in TestRegistries.extraFormats }.map { it.id }.toSet()

    private companion object {
        const val MIN_MONEY = 200
        const val MIN_PER_FORMAT = 5
        const val MIN_OTP = 10
        const val MIN_INJECTION = 10
    }

    @Test
    fun `every shipped Bank_Format has at least one fixture`() {
        val covered = fixtures.filter { it.bank != "none" && it.bank != "injection" }.map { it.group }.toSet()
        val missing = shippedFormatIds - covered
        assertTrue(missing.isEmpty(), "Bank_Formats with no fixture: $missing")
    }

    @Test
    fun `Phase 7 fixture counts are met`() {
        // Requirement 64.1: at least 200 Money_Messages, at least 5 per Bank_Format, 10 OTP, 10 injection.
        val money = fixtures.count { it.bank != "none" && it.bank != "injection" }
        assertTrue(money >= MIN_MONEY, "only $money Money_Message fixtures, need >= $MIN_MONEY")
        shippedFormatIds.forEach { id ->
            val perFormat = fixtures.count { it.group == id }
            assertTrue(perFormat >= MIN_PER_FORMAT, "Bank_Format '$id' has $perFormat fixtures, need >= $MIN_PER_FORMAT")
        }
        val otp = fixtures.count { it.result == "OTP" }
        assertTrue(otp >= MIN_OTP, "only $otp OTP fixtures, need >= $MIN_OTP")
        val injection = fixtures.count { it.injection }
        assertTrue(injection >= MIN_INJECTION, "only $injection injection fixtures, need >= $MIN_INJECTION")
    }

    @Test
    fun `every fixture is filed under a real Bank_Format or a known category, with a matching source and format id`() {
        val categories = mapOf(
            "none" to setOf("no-match", "otp-keyword", "invalid-amount", "invalid-date", "future-date"),
            "injection" to setOf("sms", "app"),
        )
        fixtures.forEach { f ->
            val known = categories[f.bank]
            if (known != null) {
                assertTrue(f.group in known, "${f.id}: '${f.group}' is not a known ${f.bank} category $known")
            } else {
                assertTrue(f.group in shippedFormatIds, "${f.id}: no Bank_Format with id '${f.group}'")
                if (f.result == "PARSED" || f.result == "FAILED") {
                    assertTrue(f.formatId == f.group, "${f.id}: expected format_id '${f.formatId}' differs from its directory")
                }
            }
            val expectedSource = if (f.origin.contains('.') && !f.origin.contains('-')) "APP" else "SMS"
            assertTrue(f.source == expectedSource, "${f.id}: source ${f.source} does not suit origin ${f.origin}")
            if (f.bank == "injection") {
                assertTrue(f.injection && f.source.equals(f.group, ignoreCase = true), "${f.id}: injection fixture must be flagged and filed by source")
                assertTrue(Regex("(?i)ignore|instruction|system:|forward this|disable|delete|transfer").containsMatchIn(f.text), "${f.id}: no instruction text")
            } else {
                assertTrue(!f.injection, "${f.id}: only injection/ fixtures are flagged as injections")
            }
        }
    }

    @Test
    fun `at least one fixture for each Currency_Marker form`() {
        val markers = listOf("Rs ", "Rs.", "INR", "\u20B9")
        markers.forEach { marker ->
            val has = fixtures.any { it.text.contains(marker, ignoreCase = true) }
            assertTrue(has, "no fixture uses the Currency_Marker '$marker'")
        }
    }

    @Test
    fun `Indian comma grouping and 0, 1 and 2 decimal places are each covered`() {
        val parsed = fixtures.filter { it.result == "PARSED" }
        assertTrue(parsed.any { Regex("""\d{1,2}(?:,\d{2})+,\d{3}""").containsMatchIn(it.text) }, "no parsed fixture has Indian comma grouping")
        // Decimal places of each amount written in a parsed fixture, with the marker before or after the number.
        val amount = Regex("""(?i)(?:rs\.?|inr|\u20B9)\s?\d[\d,]*(?:\.(\d+))?|\d[\d,]*(?:\.(\d+))?(?:/-)?\s?(?:inr|rs)\b""")
        val decimals = parsed.flatMap { f -> amount.findAll(f.text).map { it.groupValues[1].length + it.groupValues[2].length }.toList() }.toSet()
        listOf(0, 1, 2).forEach { assertTrue(it in decimals, "no parsed fixture has an amount with $it decimal places (found $decimals)") }
    }

    @Test
    fun `each date form from the requirement's examples is covered`() {
        val forms = listOf(
            Regex("(?<![\\d-])\\d{2}-\\d{2}-\\d{2}\\b"), // 05-01-25
            Regex("\\d{2}/\\d{2}/\\d{4}\\b"), // 05/01/2025
            Regex("\\d{2}-[A-Za-z]{3}-\\d{2}\\b"), // 05-Jan-25
            Regex("\\d{2}[A-Za-z]{3}\\d{4}\\b"), // 05JAN2025
        )
        forms.forEach { form -> assertTrue(fixtures.any { form.containsMatchIn(it.text) }, "no fixture matches date form $form") }
        assertTrue(fixtures.any { Regex("\\d{2}:\\d{2}(?![\\d:])").containsMatchIn(it.text) }, "no fixture has a 24-hour time")
        assertTrue(fixtures.any { Regex("\\d\\s?[AaPp][Mm]\\b").containsMatchIn(it.text) }, "no fixture has a 12-hour time")
    }

    @Test
    fun `a Reversal, a Failed_Payment_Message and an OTP with an amount are each covered`() {
        assertTrue(fixtures.any { it.reversal }, "no Reversal fixture")
        assertTrue(fixtures.any { it.result == "FAILED" }, "no Failed_Payment_Message fixture")
        assertTrue(fixtures.any { it.result == "OTP" && Regex("\\d").containsMatchIn(it.text) }, "no OTP fixture with an amount")
    }

    @Test
    fun `a message matching no Bank_Format is covered`() {
        assertTrue(fixtures.any { it.result == "UNKNOWN" && it.reason == "NO_MATCH" }, "no no-match fixture")
    }

    @Test
    fun `every message source Phase 3 reads has a Prompt_Injection_Fixture`() {
        val sourcesRead = setOf("SMS", "APP") // Phase 3 reads SMS and app notifications; email starts in Phase 6.
        val injectionSources = fixtures.filter { it.injection }.map { it.source }.toSet()
        val missing = sourcesRead - injectionSources
        assertTrue(missing.isEmpty(), "message sources with no Prompt_Injection_Fixture: $missing")
    }

    @Test
    fun `a Prompt_Injection_Fixture's instruction names no real host and the app would act only on settings and rules`() {
        val fixturesWithInjection = fixtures.filter { it.injection }
        assertTrue(fixturesWithInjection.isNotEmpty())
        fixturesWithInjection.forEach { f ->
            val hosts = Regex("""https?://([A-Za-z0-9.-]+)""").findAll(f.text).map { it.groupValues[1] }.toSet()
            assertTrue(hosts.all { it in synthetic.injectionHosts }, "${f.id}: real-looking host in fixture text: $hosts")
        }
    }

    @Test
    fun `every name, account digit, VPA, phone and reference in a fixture is a synthetic value`() {
        val fieldReference = Regex("""\bRef(?:erence)?\.?\s*\*{0,2}([A-Za-z0-9]{4,})""")
        val utrReference = Regex("""\bUTR(\d{4,})""")
        val vpa = Regex("""[A-Za-z0-9._-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*""")
        val account4 = Regex("""XX(\d{4})""")

        fixtures.forEach { f ->
            fieldReference.findAll(f.text).map { it.groupValues[1] }.forEach {
                assertTrue(it in synthetic.references, "${f.id}: reference '$it' is not a listed synthetic value")
            }
            utrReference.findAll(f.text).forEach {
                assertTrue("UTR" + it.groupValues[1] in synthetic.references, "${f.id}: reference 'UTR${it.groupValues[1]}' is not a listed synthetic value")
            }
            vpa.findAll(f.text).map { it.value }.filterNot { it.substringAfter('@') in synthetic.injectionHosts }.forEach {
                assertTrue(it in synthetic.vpas, "${f.id}: VPA or email '$it' is not a listed synthetic value")
            }
            account4.findAll(f.text).map { it.groupValues[1] }.forEach {
                assertTrue(it in synthetic.account4, "${f.id}: account digits '$it' are not a listed synthetic value")
            }
            listOf(f.account4, f.reference, f.merchantOrVpa).filterNotNull().forEach { v ->
                val known = v in synthetic.account4 || v in synthetic.references || v in synthetic.merchants || v in synthetic.vpas || v in synthetic.names
                assertTrue(known, "${f.id}: expected value '$v' is not in the synthetic-value lists")
            }
        }
    }

    @Test
    fun `no fixture's text contains a plausible 10-digit phone number`() {
        val phone = Regex("""(?<!\d)[6-9]\d{9}(?!\d)""")
        fixtures.forEach { f ->
            phone.findAll(f.text).map { it.value }.forEach {
                assertTrue(it in synthetic.phones, "${f.id}: looks like a real phone number: $it")
            }
        }
    }
}
