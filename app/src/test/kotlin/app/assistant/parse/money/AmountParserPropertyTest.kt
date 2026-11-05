// Feature: Phase 3 Money — Property 34: Amount parsing round trip.
package app.assistant.parse.money

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Paise
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private enum class TestGrouping { NONE, INDIAN, INTERNATIONAL }

private data class Rendering(val text: String, val expected: Paise)

private const val RUPEE = "\u20B9"
private val markers = listOf("Rs", "Rs.", "INR", RUPEE)

private fun group(digits: String, style: TestGrouping): String = when (style) {
    TestGrouping.NONE -> digits
    TestGrouping.INTERNATIONAL -> digits.reversed().chunked(3).joinToString(",").reversed()
    TestGrouping.INDIAN -> if (digits.length <= 3) {
        digits
    } else {
        val head = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        "$head,${digits.takeLast(3)}"
    }
}

private fun caseVariant(marker: String, mode: Int): String = when (mode) {
    0 -> marker
    1 -> marker.uppercase()
    else -> marker.lowercase()
}

private val arbRendering: Arb<Rendering> = arbitrary {
    // Mix small, mid and large values so every TestGrouping depth and the upper bound are exercised.
    val value = when (Arb.long(0L..3L).bind()) {
        0L -> Arb.long(1L..999L).bind()
        1L -> Arb.long(1L..9_999_999L).bind()
        2L -> Arb.long(1L..Paise.MAX_VALID.value).bind()
        else -> Arb.element(1L, 99L, 100L, 100_000L, Paise.MAX_VALID.value).bind()
    }
    val rupees = (value / 100).toString()
    val cents = (value % 100).toString().padStart(2, '0')
    // 0, 1 or 2 decimal places "where the value allows".
    val decimals = when {
        value % 100 == 0L -> Arb.element(0, 1, 2).bind()
        value % 10 == 0L -> Arb.element(1, 2).bind()
        else -> 2
    }
    val fraction = when (decimals) {
        0 -> ""
        1 -> "." + cents.take(1)
        else -> ".$cents"
    }
    val number = group(rupees, Arb.element(TestGrouping.entries).bind()) + fraction
    val marker = caseVariant(Arb.element(markers).bind(), Arb.long(0L..2L).bind().toInt())
    val space = if (Arb.boolean().bind()) " " else ""
    val slash = if (Arb.boolean().bind()) "/-" else ""
    val text = if (Arb.boolean().bind()) "$marker$space$number$slash" else "$number$slash$space$marker"
    Rendering(text, Paise(value))
}

class AmountParserPropertyTest {

    @Test
    fun `Property 34 - every allowed rendering parses to the same paise`() = runTest {
        checkAll(arbRendering) { r ->
            assertEquals(Outcome.Ok(r.expected), AmountParser.parse(r.text), "rendering: ${r.text}")
        }
    }

    @Test
    fun `the examples from the requirement`() {
        mapOf(
            "Rs 1,234" to 123_400L,
            "INR 1,234.5" to 123_450L,
            "${RUPEE}1,23,456.78" to 12_345_678L,
            "Rs.500/-" to 50_000L,
            "250.00 INR" to 25_000L,
        ).forEach { (text, paise) -> assertEquals(Outcome.Ok(Paise(paise)), AmountParser.parse(text), text) }
    }

    @Test
    fun `more than 2 decimal places is rejected`() = runTest {
        checkAll(Arb.long(1L..999_999L), Arb.long(100L..99_999L)) { rupees, fraction ->
            assertEquals(Outcome.Err(AmountError.TOO_MANY_DECIMALS), AmountParser.parse("Rs $rupees.$fraction"))
        }
    }

    @Test
    fun `amounts outside Valid_Amount are rejected`() {
        listOf("Rs 0", "Rs 0.00", "INR 1,00,00,00,000", "Rs 99999999999", "Rs 123456789012345678901234567890").forEach {
            assertEquals(Outcome.Err(AmountError.OUT_OF_RANGE), AmountParser.parse(it), it)
        }
        assertEquals(Outcome.Ok(Paise.MAX_VALID), AmountParser.parse("Rs 99,99,99,999.99"))
        assertEquals(Outcome.Ok(Paise.MIN_VALID), AmountParser.parse("Rs 0.01"))
    }

    @Test
    fun `malformed tokens are rejected`() {
        listOf("", "Rs", "abc", "Rs Rs 5", "Rs 5 INR", "1,23,45", "12,34", "1,2345", "-500", "Rs 5.", "5..0", "Rs 1e5").forEach {
            assertEquals(Outcome.Err(AmountError.MALFORMED), AmountParser.parse(it), "should reject '$it'")
        }
    }
}
