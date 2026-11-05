package app.assistant.parse.money

import app.assistant.domain.model.Paise
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Task 18.6: what the Message_Parser decides for each kind of message (Requirement 28.1 to 28.5, 28.9 to 28.11). */
class MessageParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val received = LocalDateTime.of(2025, 1, 5, 20, 0).atZone(zone).toInstant()

    private fun parse(text: String, origin: String = TestRegistries.BANK_A) = MessageParser.parse(text, origin, received, zone, TestRegistries.shipped)

    private fun parsed(m: ParsedMessage, id: String) = ParseResult.Parsed(m, id)

    private fun unknown(reason: UnknownReason) = ParseResult.Unknown(reason)

    private val debit = "Rs 1,234.50 debited from a/c XX1234 on 05-01-25 14:05 at Cafe Blue. Avl Bal Rs 20,000.00"

    @Test
    fun `a debit gives every field, with the account reduced to its last 4 digits`() {
        val expected = ParsedMessage(
            MessageType.DEBIT,
            false,
            Paise(123_450),
            LocalDateTime.of(2025, 1, 5, 14, 5),
            "Cafe Blue",
            "1234",
            null,
            Paise(2_000_000),
        )
        assertEquals(parsed(expected, "bankA.debit.v1"), parse(debit))
    }

    @Test
    fun `a credit with a 12-hour time, a reference and a zero balance`() {
        val text = "Rs 500 credited to a/c XX1234 on 05-Jan-25 02:05 PM. Ref AB12CD34. Avl Bal Rs 0.00"
        val expected = ParsedMessage(
            MessageType.CREDIT,
            false,
            Paise(50_000),
            LocalDateTime.of(2025, 1, 5, 14, 5),
            null,
            "1234",
            "AB12CD34",
            Paise(0),
        )
        assertEquals(parsed(expected, "bankA.credit.v1"), parse(text))
    }

    @Test
    fun `a bill gives its due date, which may be in the future`() {
        val text = "Your BankA Credit Card bill of Rs 3,456.00 is due on 20-01-25. Pay in the BankA app."
        val expected = ParsedMessage(MessageType.BILL, false, Paise(345_600), LocalDateTime.of(2025, 1, 20, 0, 0), null, null, null, null)
        assertEquals(parsed(expected, "bankA.bill.v1"), parse(text))
    }

    @Test
    fun `a refund or reversal is a credit marked as a reversal`() {
        val result = parse("Rs 99 has been reversed/refunded to your a/c XX1234 on 05-01-25. Ref ZX90QW12.")
        val message = (result as ParseResult.Parsed).message
        assertEquals(MessageType.CREDIT, message.type)
        assertTrue(message.isReversal)
        assertEquals("bankA.reversal.v1", result.formatId)
    }

    @Test
    fun `a missing time of day is taken from the Received_Time`() {
        val text = "INR 1,23,456.78 spent on your BankB Card XX4321 at Store Two on 05/01/2025. Call 1800-xxx if not you."
        val message = (parse(text, TestRegistries.BANK_B) as ParseResult.Parsed).message
        assertEquals(LocalDateTime.of(2025, 1, 5, 20, 0), message.at)
        assertEquals(Paise(12_345_678), message.amount)
        assertEquals("4321", message.account4)
    }

    @Test
    fun `a payment app message gives the VPA as the merchant`() {
        val text = "Paid Rs250 to shop@upi on 2025-01-05 at 14:05. UPI Ref 1234567890."
        val message = (parse(text, TestRegistries.UPI_APP) as ParseResult.Parsed).message
        assertEquals("shop@upi", message.merchantOrVpa)
        assertEquals("1234567890", message.reference)
        assertEquals(LocalDateTime.of(2025, 1, 5, 14, 5), message.at)
    }

    @Test
    fun `a failed or declined payment is classified and carries no fields`() {
        assertEquals(
            ParseResult.FailedPayment("bankA.failed.v1"),
            parse("Your transaction of Rs 99 at Shop One on 05-01-25 has failed/declined. Ref ZX90QW12."),
        )
        assertEquals(
            ParseResult.FailedPayment("upiapp.failed.v1"),
            parse("Payment of Rs250 to shop@upi failed. UPI Ref 1234567890.", TestRegistries.UPI_APP),
        )
    }

    @Test
    fun `an OTP is recognised before any other format, including when it holds an amount`() {
        assertEquals(ParseResult.Otp, parse("123456 is your OTP for a transaction of Rs 250.5 at Shop One. Valid for 10 mins."))
        // No Bank_Format matches this one; the keyword alone makes it an OTP and not a Review_Queue item.
        assertEquals(ParseResult.Otp, parse("Use 4821 as your one time password for Rs 500 at Shop"))
        assertEquals(ParseResult.Otp, parse("Your VERIFICATION CODE is 998877", origin = "SOME-OTHER-SENDER"))
        // A debit message that mentions an OTP keyword is still an OTP message (Requirement 28.3).
        assertEquals(ParseResult.Otp, parse("$debit Never share your OTP."))
    }

    @Test
    fun `an OTP keyword is a whole word`() {
        assertEquals(unknown(UnknownReason.NO_MATCH), parse("Enjoy hotpot with your friends for Rs 500"))
    }

    @Test
    fun `no matching format, an unknown sender or an oversized message is unknown with no match`() {
        assertEquals(unknown(UnknownReason.NO_MATCH), parse("Hello from BankA, thanks for banking with us"))
        assertEquals(unknown(UnknownReason.NO_MATCH), parse(debit, origin = "UNKNOWN-SENDER"))
        // Over 2,000 characters: not matched at all, even for an OTP keyword (design: Parser_Registry).
        assertEquals(unknown(UnknownReason.NO_MATCH), parse("OTP " + "a".repeat(2_001)))
        assertEquals(unknown(UnknownReason.NO_MATCH), parse(debit + " ".repeat(10) + "x".repeat(2_000)))
    }

    @Test
    fun `an amount that is not valid makes the message unknown`() {
        fun withAmount(a: String) = "Rs $a debited from a/c XX1234 on 05-01-25 14:05 at Cafe Blue. Avl Bal Rs 20,000.00"
        listOf("0", "0.00", "1.234", "999999999999", "1,23,45").forEach {
            assertEquals(unknown(UnknownReason.INVALID_AMOUNT), parse(withAmount(it)), it)
        }
        assertEquals(unknown(UnknownReason.INVALID_AMOUNT), parse(debit.replace("20,000.00", "99999999999999")))
    }

    @Test
    fun `an impossible date or time makes the message unknown`() {
        assertEquals(unknown(UnknownReason.INVALID_DATE), parse(debit.replace("05-01-25", "31-02-25")))
        assertEquals(unknown(UnknownReason.INVALID_DATE), parse(debit.replace("14:05", "25:61")))
    }

    @Test
    fun `a debit or credit dated more than 1 day after the Received_Time is unknown`() {
        assertEquals(unknown(UnknownReason.FUTURE_DATE), parse(debit.replace("05-01-25", "08-01-25")))
        assertTrue(parse(debit.replace("05-01-25", "06-01-25")) is ParseResult.Parsed)
    }

    @Test
    fun `the parser never throws, whatever the text`() = runTest {
        checkAll(Arb.string(0..400), Arb.string(0..40)) { text, origin ->
            val result = MessageParser.parse(text, origin, received, zone, TestRegistries.withExtras)
            assertTrue(result is ParseResult)
        }
        assertTrue(parse("  \n$debit\n  ") is ParseResult.Parsed, "surrounding whitespace is ignored")
    }
}
