package app.assistant.domain.model

import java.time.Instant
import java.time.LocalDate

/** A note the User typed. Their own words, kept encrypted on the phone. */
data class Note(val id: Long, val title: String, val body: String, val pinned: Boolean, val updatedAt: Instant)

/** One day's journal entry. */
data class JournalEntry(val date: LocalDate, val text: String)

/** One line on a list (groceries, packing…). With [repeatDays] it comes back that many days after it was done. */
data class ListItem(val id: Long, val list: String, val title: String, val done: Boolean, val repeatDays: Int?, val doneOn: LocalDate?)

/** Someone the User wants to stay in touch with, every [everyDays] days. */
data class KeepInTouch(val id: Long, val name: String, val everyDays: Int, val lastTalked: LocalDate?)

enum class TripKind { TRAIN, FLIGHT, BUS, HOTEL }

/** A journey or stay found in a ticket mail or SMS (or added by hand). [sourceRef] keeps one row per booking. */
data class Trip(
    val id: Long,
    val kind: TripKind,
    val title: String,
    val ref: String?,
    val startsAt: Instant,
    val from: String?,
    val to: String?,
    val sourceRef: String,
)

enum class OrderStatus { ORDERED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }

/** An online order found in mail or SMS. [orderRef] is the shop's order number, so updates land on the same row. */
data class Order(
    val id: Long,
    val merchant: String,
    val orderRef: String,
    val status: OrderStatus,
    val expectedOn: LocalDate?,
    val returnBy: LocalDate?,
    val updatedAt: Instant,
)
