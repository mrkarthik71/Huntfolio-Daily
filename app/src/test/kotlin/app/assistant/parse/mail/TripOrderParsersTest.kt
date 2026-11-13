package app.assistant.parse.mail

import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.TripKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Synthetic, anonymised samples only (CLAUDE.md). Names, numbers and references are made up. */
class TripOrderParsersTest {
    private val received = LocalDate.of(2026, 11, 13)

    @Test
    fun aTrainBookingGivesPnrTrainDateTimeAndRoute() {
        val t = TripParser.parse(
            "IRCTC <ticketadmin@irctc.co.in>",
            "Booking Confirmation on IRCTC, Train: 12627, 15-Dec-2026, SL, SBC - NDLS",
            "PNR No. : 4521234567\nTrain No./Name : 12627 / KARNATAKA EXP\nFrom: KSR BENGALURU To: NEW DELHI\n" +
                "Date of Journey: 15-Dec-2026\nDeparture: 19:20",
            received,
        )
        assertNotNull(t)
        t!!
        assertEquals(TripKind.TRAIN, t.kind)
        assertEquals("4521234567", t.ref)
        assertEquals(LocalDate.of(2026, 12, 15), t.date)
        assertEquals(LocalTime.of(19, 20), t.time)
        assertEquals("12627 KARNATAKA EXP", t.title)
        assertEquals("KSR BENGALURU", t.from)
        assertEquals("NEW DELHI", t.to)
    }

    @Test
    fun aFlightBookingGivesFlightNumberAndRef() {
        val t = TripParser.parse(
            "Example Air <noreply@example-air.in>",
            "Your flight booking is confirmed - PNR XQ7K2L",
            "Flight 6E 2134 BLR → DEL\nDeparture date: 20 Dec 2026\nDeparts at 6:45 am\nWeb check-in opens 48 hours before.",
            received,
        )!!
        assertEquals(TripKind.FLIGHT, t.kind)
        assertEquals("XQ7K2L", t.ref)
        assertEquals("6E 2134", t.title)
        assertEquals(LocalDate.of(2026, 12, 20), t.date)
        assertEquals(LocalTime.of(6, 45), t.time)
        assertEquals("BLR", t.from)
        assertEquals("DEL", t.to)
    }

    @Test
    fun aHotelBookingUsesTheHotelName() {
        val t = TripParser.parse(
            "Stays <bookings@example-stays.com>",
            "Your booking at Sea View Residency is confirmed",
            "Booking ID: HB99812\nCheck-in date: 24 Dec 2026\nCheck-in time 14:00",
            received,
        )!!
        assertEquals(TripKind.HOTEL, t.kind)
        assertEquals("Sea View Residency", t.title)
        assertEquals(LocalDate.of(2026, 12, 24), t.date)
    }

    @Test
    fun cancellationsPromotionsAndOldTripsAreIgnored() {
        assertNull(TripParser.parse("IRCTC <a@irctc.co.in>", "Ticket cancelled, refund initiated", "PNR 4521234567 Date of Journey: 15-Dec-2026", received))
        assertNull(TripParser.parse("Shop <a@shop.in>", "Big sale this weekend", "Up to 50% off", received))
        assertNull(TripParser.parse("IRCTC <a@irctc.co.in>", "Booking confirmation", "PNR 4521234567 train 12627 Date of Journey: 01-Oct-2026", received))
    }

    @Test
    fun ordersAreReadWithStatusAndDates() {
        val placed = OrderParser.parse(
            "Amazon.in <order-update@amazon.in>",
            "Your Amazon.in order #402-1234567-7654321",
            "Thank you for your order. Arriving Tuesday, 18 Nov",
            received,
        )!!
        assertEquals("402-1234567-7654321", placed.orderRef)
        assertEquals(OrderStatus.ORDERED, placed.status)
        assertEquals(LocalDate.of(2026, 11, 18), placed.expectedOn)
        assertEquals("Amazon.in", placed.merchant)

        val out = OrderParser.parse(
            "Flipkart <noreply@flipkart.com>",
            "Out for delivery",
            "Your order OD123456789012345678 is out for delivery today",
            received,
        )!!
        assertEquals(OrderStatus.OUT_FOR_DELIVERY, out.status)
        assertEquals(received, out.expectedOn)

        val done = OrderParser.parse(
            "Shop <noreply@shop.example>",
            "Delivered: your order",
            "Order ID: SHP77881122 has been delivered. Return window closes on 23 Nov 2026",
            received,
        )!!
        assertEquals(OrderStatus.DELIVERED, done.status)
        assertEquals(LocalDate.of(2026, 11, 23), done.returnBy)
    }

    @Test
    fun ordersNeedAnOrderNumberAndSkipOffers() {
        assertNull(OrderParser.parse("Shop <a@shop.example>", "Your order has shipped", "It is on its way", received))
        assertNull(OrderParser.parse("Shop <a@shop.example>", "Flat 40% off on your next order", "Order ID: SHP1 code", received))
    }

    @Test
    fun emiMailIsNowABill() {
        val r = ReceiptParser.parse("Example Finance <emi@example-fin.in>", "EMI due reminder", "Your EMI of Rs. 8,450 is due on 05 Dec 2026", received)
        assertNotNull(r)
        assertEquals(845_000L, r!!.amountPaise)
        assertEquals(LocalDate.of(2026, 12, 5), r.dueDate)
    }
}
