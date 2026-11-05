// Feature: Phase 3 Money — Property 36: Bank_Format template round trip.
package app.assistant.parse.money

import app.assistant.domain.model.Paise
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val zones = listOf(ZoneId.of("UTC"), ZoneId.of("Asia/Kolkata"))
private val markers = listOf("", "Rs", "rs", "RS", "Rs.", "INR", "inr", "\u20B9")
private const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private val arbStyle: Arb<RenderStyle> = arbitrary {
    RenderStyle(
        amountMarker = Arb.element(markers).bind(),
        markerAfter = Arb.boolean().bind(),
        markerSpace = Arb.boolean().bind(),
        slashSuffix = Arb.boolean().bind(),
        grouping = Arb.element(Grouping.entries).bind(),
        decimals = Arb.int(0..2).bind(),
        monthStyle = Arb.element(MonthStyle.entries).bind(),
        fourDigitYear = Arb.boolean().bind(),
        padDate = Arb.boolean().bind(),
        separatorIndex = Arb.int(0..3).bind(),
        twelveHour = Arb.boolean().bind(),
        seconds = Arb.boolean().bind(),
        meridiemSpace = Arb.boolean().bind(),
        meridiemLowerCase = Arb.boolean().bind(),
        maskAccount = Arb.boolean().bind(),
    )
}

/** Amounts across small, mid, large and boundary values; a balance may also be zero. */
private fun arbPaise(allowZero: Boolean): Arb<Paise> = arbitrary {
    val value = when (Arb.int(0..4).bind()) {
        0 -> Arb.long(1L..999L).bind()
        1 -> Arb.long(1L..9_999_999L).bind()
        2 -> Arb.long(1L..Paise.MAX_VALID.value).bind()
        3 -> Arb.element(1L, 100L, 100_000L, Paise.MAX_VALID.value).bind()
        else -> if (allowZero) 0L else Arb.long(1L..999L).bind()
    }
    Paise(value)
}

private fun arbWord(chars: String, length: IntRange): Arb<String> = arbitrary {
    (1..Arb.int(length).bind()).map { chars[Arb.int(chars.indices).bind()] }.joinToString("")
}

/** One to three words of letters and digits separated by single spaces, never starting or ending with a space. */
private val arbWords: Arb<String> = arbitrary {
    val count = Arb.int(1..3).bind()
    val words = ArrayList<String>()
    repeat(count) { words += arbWord(ALPHANUMERIC, 2..10).bind() }
    words.joinToString(" ")
}

/** A Parsed_Message with exactly the fields [BankFormat] has places for, so the format can hold it. */
private val arbCase: Arb<Triple<BankFormat, ParsedMessage, RenderStyle>> = arbitrary {
    val format = Arb.element(TestRegistries.allFormats.filter { it.kind.type != null }).bind()
    val style = arbStyle.bind()
    val fields = format.template.fields.toSet()
    val date = LocalDate.of(2001, 1, 1).plusDays(Arb.long(0L..35_000L).bind())
    val second = if (FieldName.TIME in fields && style.seconds) Arb.int(0..59).bind() else 0
    val time = LocalTime.of(Arb.int(0..23).bind(), Arb.int(0..59).bind(), second)
    val at = if (format.kind == FormatKind.BILL) date.atStartOfDay() else LocalDateTime.of(date, time)
    val merchantOrVpa = when {
        FieldName.VPA in fields -> arbWord("abcdefghij0123456789", 3..10).bind() + "@" + arbWord("abcdefghij", 3..8).bind()
        FieldName.MERCHANT in fields -> arbWords.bind()
        else -> null
    }
    val message = ParsedMessage(
        type = checkNotNull(format.kind.type),
        isReversal = format.kind.reversal,
        amount = arbPaise(allowZero = false).bind(),
        at = at,
        merchantOrVpa = merchantOrVpa,
        account4 = if (FieldName.ACCOUNT4 in fields) arbWord("0123456789", 4..4).bind() else null,
        reference = if (FieldName.REFERENCE in fields) arbWord(ALPHANUMERIC, 4..24).bind() else null,
        balance = if (FieldName.BALANCE in fields) arbPaise(allowZero = true).bind() else null,
    )
    Triple(format, message, style)
}
class TemplateRoundTripPropertyTest {

    @Test
    fun `Property 36 - rendering a Parsed_Message then parsing it gives the same Parsed_Message`() = runTest {
        checkAll(arbCase, Arb.element(zones)) { (format, message, style), zone ->
            assertTrue(TemplateFormatter.canHold(format, message), "${format.id} should hold $message")
            val text = TemplateFormatter.render(format, message, style)
            // The Received_Time supplies whatever the format does not hold; a bill is received before it falls due.
            val sent = if (format.kind == FormatKind.BILL) message.at.minusDays(5) else message.at
            val result = MessageParser.parse(text, TestRegistries.originFor(format), sent.atZone(zone).toInstant(), zone, TestRegistries.withExtras)
            assertEquals(ParseResult.Parsed(message, format.id), result, "text: $text")
        }
    }

    @Test
    fun `a format that has no place for a field cannot hold the message`() {
        val format = TestRegistries.allFormats.first { it.id == "bankA.debit.v1" }
        val full = ParsedMessage(MessageType.DEBIT, false, Paise(5_000), LocalDateTime.of(2025, 1, 5, 14, 5), "Cafe", "1234", null, Paise(100))
        assertTrue(TemplateFormatter.canHold(format, full))
        assertTrue(!TemplateFormatter.canHold(format, full.copy(reference = "ABCD1234")), "no place for a reference")
        assertTrue(!TemplateFormatter.canHold(format, full.copy(merchantOrVpa = null)), "the format needs a merchant")
        assertTrue(!TemplateFormatter.canHold(format, full.copy(type = MessageType.CREDIT)), "a debit format cannot hold a credit")
        assertTrue(!TemplateFormatter.canHold(format, full.copy(isReversal = true)), "a debit format cannot hold a reversal")
        assertTrue(!TemplateFormatter.canHold(format, full.copy(merchantOrVpa = "line\nbreak")), "a field value the format would not accept")
    }

    @Test
    fun `OTP and failed-payment formats hold no Parsed_Message`() {
        val message = ParsedMessage(MessageType.DEBIT, false, Paise(5_000), LocalDateTime.of(2025, 1, 5, 14, 5), "Shop One", null, null, null)
        listOf("bankA.otp.v1", "bankA.failed.v1", "upiapp.failed.v1").forEach { id ->
            assertTrue(!TemplateFormatter.canHold(TestRegistries.allFormats.first { it.id == id }, message), id)
        }
    }

    @Test
    fun `the amount forms of the requirement render and read back`() {
        val plain = RenderStyle(amountMarker = "")
        assertEquals("1,234", TemplateFormatter.amount(Paise(123_400), plain.copy(decimals = 0)))
        assertEquals("1,234.5", TemplateFormatter.amount(Paise(123_450), plain.copy(decimals = 1)))
        assertEquals("\u20B91,23,456.78", TemplateFormatter.amount(Paise(12_345_678), plain.copy(amountMarker = "\u20B9")))
        assertEquals("Rs.500/-", TemplateFormatter.amount(Paise(50_000), plain.copy(amountMarker = "Rs.", slashSuffix = true, decimals = 0)))
        assertEquals("250.00 INR", TemplateFormatter.amount(Paise(25_000), plain.copy(amountMarker = "INR", markerAfter = true, markerSpace = true)))
    }
}
