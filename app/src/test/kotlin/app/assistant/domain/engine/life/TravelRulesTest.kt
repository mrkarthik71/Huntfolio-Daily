package app.assistant.domain.engine.life

import app.assistant.domain.model.Order
import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.Trip
import app.assistant.domain.model.TripKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TravelRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDate.of(2026, 11, 13).atTime(10, 0).atZone(zone).toInstant()

    private fun trip(id: Long, day: LocalDate, hour: Int?, minute: Int = 0) = Trip(
        id,
        TripKind.TRAIN,
        "12627",
        "4521234567",
        (if (hour == null) day.atStartOfDay(zone) else day.atTime(hour, minute).atZone(zone)).toInstant(),
        null,
        null,
        "trip:$id",
    )

    @Test
    fun aTimedTripGetsAnEveningBeforeAndAThreeHourReminder() {
        val a = TripRules.alerts(listOf(trip(1, LocalDate.of(2026, 11, 15), 6, 45)), now, zone)
        assertEquals(listOf("trip:1:eve", "trip:1:soon"), a.map { it.refKey })
        assertEquals(LocalDate.of(2026, 11, 14).atTime(19, 0).atZone(zone).toInstant(), a[0].at)
        assertEquals(LocalDate.of(2026, 11, 15).atTime(3, 45).atZone(zone).toInstant(), a[1].at)
    }

    @Test
    fun aTripWithoutATimeOnlyGetsTheEveningBefore() {
        assertEquals(listOf("trip:2:eve"), TripRules.alerts(listOf(trip(2, LocalDate.of(2026, 11, 20), null)), now, zone).map { it.refKey })
    }

    @Test
    fun pastTripsAndPastTimesAreSkipped() {
        assertTrue(TripRules.alerts(listOf(trip(3, LocalDate.of(2026, 11, 10), 9)), now, zone).isEmpty())
        // Today at 12:00: the evening before and 09:00 have both passed.
        assertTrue(TripRules.alerts(listOf(trip(4, LocalDate.of(2026, 11, 13), 12)), now, zone).isEmpty())
    }

    @Test
    fun ordersRemindOnTheExpectedDayAndBeforeTheReturnWindowCloses() {
        val today = LocalDate.of(2026, 11, 13)
        val orders = listOf(
            Order(1, "Shop", "A1", OrderStatus.SHIPPED, today.plusDays(2), null, Instant.EPOCH),
            Order(2, "Shop", "A2", OrderStatus.DELIVERED, null, today.plusDays(5), Instant.EPOCH),
            Order(3, "Shop", "A3", OrderStatus.CANCELLED, today, null, Instant.EPOCH),
            Order(4, "Shop", "A4", OrderStatus.DELIVERED, null, null, Instant.EPOCH),
        )
        assertEquals(
            listOf("order:1:eta:2026-11-15" to today.plusDays(2), "order:2:ret:2026-11-17" to today.plusDays(4)),
            OrderRules.alerts(orders, today),
        )
        assertEquals(listOf(1L, 2L), OrderRules.active(orders, today).map { it.id })
    }
}
