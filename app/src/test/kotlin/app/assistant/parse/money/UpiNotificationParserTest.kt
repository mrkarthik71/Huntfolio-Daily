package app.assistant.parse.money

import app.assistant.domain.model.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Synthetic, anonymised samples shaped like common UPI app notifications (names, ids and numbers are made up). */
class UpiNotificationParserTest {
    private val at = LocalDateTime.of(2026, 11, 13, 9, 30)

    private fun parsed(text: String) = UpiNotificationParser.parse(text, at) as ParseResult.Parsed

    @Test
    fun paymentsSent() {
        val a = parsed("Paid ₹250 to Ravi Kumar UPI Ref No 432112345678")
        assertEquals(MessageType.DEBIT, a.message.type)
        assertEquals(Paise(25_000), a.message.amount)
        assertEquals("Ravi Kumar", a.message.merchantOrVpa)
        assertEquals("432112345678", a.message.reference)

        val b = parsed("₹1,299.50 sent to example.store@okbank successfully")
        assertEquals(Paise(129_950), b.message.amount)
        assertEquals("example.store@okbank", b.message.merchantOrVpa)

        val c = parsed("Payment successful You paid Rs. 80 to Sharma Tea Stall from A/c XX1234")
        assertEquals(MessageType.DEBIT, c.message.type)
        assertEquals("1234", c.message.account4)
        assertEquals("Sharma Tea Stall", c.message.merchantOrVpa)

        val d = parsed("Paid ₹120 to Auto Driver. Available balance ₹4,500")
        assertEquals(Paise(12_000), d.message.amount)
    }

    @Test
    fun moneyReceivedAndRefunds() {
        val a = parsed("You received ₹500 from Priya S. on your bank account")
        assertEquals(MessageType.CREDIT, a.message.type)
        assertEquals("Priya S", a.message.merchantOrVpa)

        val r = parsed("Refund of ₹349 received from Example Shop")
        assertEquals(MessageType.CREDIT, r.message.type)
        assertTrue(r.message.isReversal)
    }

    @Test
    fun failedPayments() {
        assertTrue(UpiNotificationParser.parse("Payment of ₹600 to Ravi failed. Money will not be deducted", at) is ParseResult.FailedPayment)
    }

    @Test
    fun notPaymentsAreLeftAlone() {
        assertNull(UpiNotificationParser.parse("Ravi has requested ₹200 from you. Pay now", at))
        assertNull(UpiNotificationParser.parse("You won ₹25 cashback on your scratch card", at))
        assertNull(UpiNotificationParser.parse("Your electricity bill of ₹1,240 is due tomorrow", at))
        assertNull(UpiNotificationParser.parse("Check your account balance of ₹12,000", at))
        assertNull(UpiNotificationParser.parse("Hi there, how are you?", at))
    }
}
