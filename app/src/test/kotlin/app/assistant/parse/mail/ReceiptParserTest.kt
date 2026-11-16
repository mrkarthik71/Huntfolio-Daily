package app.assistant.parse.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

// Synthetic, anonymised sample messages (CLAUDE.md: parsers are tested with made-up samples).
class ReceiptParserTest {
    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun `electricity bill with amount and due date`() {
        val r = ReceiptParser.parse(
            "City Power <bills@citypower.example>",
            "Your electricity bill for October",
            "Dear customer, amount payable: Rs. 2,340.50. Due date: 15/11/2026. Pay online to avoid late fees.",
            today,
        )!!
        assertEquals("City Power", r.merchant)
        assertEquals(234_050L, r.amountPaise)
        assertEquals(LocalDate.of(2026, 11, 15), r.dueDate)
        assertTrue(!r.recurring)
    }

    @Test
    fun `card statement picks the labelled total, not the minimum`() {
        val r = ReceiptParser.parse(
            "alerts@examplebank.example",
            "Credit card statement",
            "Total amount due ₹18,420. Minimum due ₹920. Payment due on 22 Nov 2026.",
            today,
        )!!
        assertEquals(1_842_000L, r.amountPaise)
        assertEquals(LocalDate.of(2026, 11, 22), r.dueDate)
        assertEquals("Examplebank", r.merchant)
    }

    @Test
    fun `paid subscription receipt is kept as recurring`() {
        val r = ReceiptParser.parse(
            "StreamFlix <info@mailer.streamflix.example>",
            "Your StreamFlix subscription receipt",
            "Thank you for your payment of INR 649. Your plan renews on Dec 6, 2026.",
            today,
        )!!
        assertTrue(r.recurring)
        assertTrue(r.alreadyPaid)
        assertEquals(64_900L, r.amountPaise)
        assertEquals(LocalDate.of(2026, 12, 6), r.dueDate)
    }

    @Test
    fun `offers, OTPs, paid one-offs and mail without rupees are ignored`() {
        assertNull(ReceiptParser.parse("shop@x.example", "Big sale: 50% off", "Bill total Rs 499 only today", today))
        assertNull(ReceiptParser.parse("bank@x.example", "Your OTP", "Your bill OTP is 123456", today))
        assertNull(ReceiptParser.parse("cafe@x.example", "Payment receipt", "Payment successful. Rs 240 paid on 5 Nov.", today))
        assertNull(ReceiptParser.parse("team@x.example", "Invoice attached", "Please find the invoice attached.", today))
    }
}
