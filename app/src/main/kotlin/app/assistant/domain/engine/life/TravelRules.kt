package app.assistant.domain.engine.life

import app.assistant.domain.model.KeepInTouch
import app.assistant.domain.model.Order
import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.Trip
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A reminder to plan: its reference key (IDs and dates only) and when it should fire. */
data class PlannedAlert(val refKey: String, val at: Instant)

/** Trips: the evening before at 19:00, and three hours before a trip with a known time. */
object TripRules {
    val EVENING: LocalTime = LocalTime.of(19, 0)
    const val SOON_HOURS = 3L

    fun hasTime(t: Trip, zone: ZoneId): Boolean = t.startsAt.atZone(zone).toLocalTime() != LocalTime.MIDNIGHT

    fun upcoming(trips: List<Trip>, now: Instant, zone: ZoneId): List<Trip> {
        val today = now.atZone(zone).toLocalDate()
        return trips.filter { !it.startsAt.atZone(zone).toLocalDate().isBefore(today) }.sortedBy { it.startsAt }
    }

    fun alerts(trips: List<Trip>, now: Instant, zone: ZoneId): List<PlannedAlert> = upcoming(trips, now, zone).flatMap { t ->
        val day = t.startsAt.atZone(zone).toLocalDate()
        val eve = day.minusDays(1).atTime(EVENING).atZone(zone).toInstant()
        val soon = t.startsAt.minus(Duration.ofHours(SOON_HOURS))
        listOfNotNull(
            PlannedAlert("trip:${t.id}:eve", eve).takeIf { eve.isAfter(now) },
            PlannedAlert("trip:${t.id}:soon", soon).takeIf { hasTime(t, zone) && soon.isAfter(now) },
        )
    }
}

/** Orders: a morning note on the expected day while it is on the way, and one the day before the return window closes. */
object OrderRules {
    fun active(orders: List<Order>, today: LocalDate): List<Order> = orders.filter {
        when (it.status) {
            OrderStatus.CANCELLED -> false
            OrderStatus.DELIVERED -> it.returnBy?.let { r -> !r.isBefore(today) } ?: false
            else -> true
        }
    }

    fun alerts(orders: List<Order>, today: LocalDate): List<Pair<String, LocalDate>> = orders.flatMap { o ->
        listOfNotNull(
            o.expectedOn?.takeIf { o.status in ON_THE_WAY && !it.isBefore(today) }?.let { "order:${o.id}:eta:$it" to it },
            o.returnBy?.minusDays(1)?.takeIf { o.status == OrderStatus.DELIVERED && !it.isBefore(today) }?.let { "order:${o.id}:ret:$it" to it },
        )
    }

    private val ON_THE_WAY = setOf(OrderStatus.ORDERED, OrderStatus.SHIPPED, OrderStatus.OUT_FOR_DELIVERY)
}

/** Keep in touch: one morning note on a day someone is due. */
object TouchAlerts {
    fun due(people: List<KeepInTouch>, today: LocalDate): List<KeepInTouch> =
        KeepInTouchRules.ordered(people, today).filter { KeepInTouchRules.isDue(it, today) }
}
