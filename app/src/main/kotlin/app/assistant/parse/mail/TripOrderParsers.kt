package app.assistant.parse.mail

import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.TripKind
import java.time.LocalDate
import java.time.LocalTime

/** What a ticket or booking says. Only these fields leave the parser; the message itself is never kept. */
data class TripFact(val kind: TripKind, val title: String, val ref: String?, val date: LocalDate, val time: LocalTime?, val from: String?, val to: String?)

/**
 * Train, flight, bus and hotel bookings from a confirmation mail or a shared SMS, with rules only.
 * The text is untrusted: nothing in it is followed or opened. Returns null unless the message is
 * clearly a booking with a travel date.
 */
object TripParser {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val confirmWords = Regex("\\b(booked|booking|confirmed|confirmation|e-?ticket|ticket|itinerary|reservation|pnr)\\b", opt)
    private val cancelWords = Regex("\\b(cancell?ed|cancellation|refund)\\b", opt)
    private val trainWords = Regex("\\b(irctc|train|pnr|coach|berth|sleeper|chair car|3a|2a|sl class)\\b", opt)
    private val flightWords = Regex("\\b(flight|airline|airways|boarding|web check-?in|indigo|air india|vistara|akasa|spicejet)\\b", opt)
    private val busWords = Regex("\\b(bus|redbus|abhibus|ksrtc|tnstc|boarding point)\\b", opt)
    private val hotelWords = Regex("\\b(hotel|check-?in date|stay|room|resort|oyo|homestay)\\b", opt)

    private val pnr = Regex(
        "\\b(?:pnr(?:\\s*no\\.?)?|booking (?:id|ref(?:erence)?)|confirmation (?:no|number|code)|ticket no)" +
            "\\.?\\s*[:#-]?\\s*([A-Z0-9]{5,12})\\b",
        opt,
    )
    private val trainNo = Regex("\\btrain(?:\\s*no\\.?)?(?:\\s*/\\s*name)?\\s*[:#-]?\\s*(\\d{5})\\b(?:\\s*/\\s*([A-Z][A-Z .]{2,30}))?", opt)
    private val flightNo = Regex("\\b(6E|AI|UK|SG|QP|I5|IX|G8)\\s?-?(\\d{2,4})\\b")
    private val route =
        Regex(
            "\\b(?:from)\\s*[:-]?\\s*([A-Za-z][A-Za-z .()]{1,40}?)\\s+(?:to)\\s*[:-]?\\s*([A-Za-z][A-Za-z .()]{1,40}?)" +
                "(?=[\\n,;|]|\\s{2}|\\s+(?:on|date|dep)|$)",
            opt,
        )
    private val arrowRoute = Regex("\\b([A-Z]{3,4}|[A-Z][a-z]{2,20})\\s*(?:→|->|-|to)\\s*([A-Z]{3,4}|[A-Z][a-z]{2,20})\\b")
    private val dateLabel = Regex(
        "(?:date of journey|journey date|doj|travel date|departure(?: date)?|check-?in(?: date)?|date)\\s*[:-]?\\s*" +
            "([0-9]{1,2}[ /\\-.][A-Za-z0-9]{1,9}[ /\\-.,]*[0-9]{2,4}|[A-Za-z]{3,9} [0-9]{1,2},? [0-9]{4}|[0-9]{1,2} [A-Za-z]{3,9})",
        opt,
    )
    private val timeLabel = Regex("(?:dep(?:arture|arts)?|boarding|check-?in)(?: time)?\\s*[:-]?\\s*(?:at\\s*)?([0-2]?\\d)[:.]([0-5]\\d)\\s*(am|pm)?", opt)
    private val hotelName = Regex("(?:booking at|stay at|reservation at|welcome to)\\s+([A-Z][A-Za-z0-9 &'.-]{2,50}?)(?:\\s+is|\\s+on|,|\\.|$)", opt)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // each early return rejects one kind of mail that is not a booking
    fun parse(sender: String, subject: String, body: String, received: LocalDate): TripFact? {
        val text = "$subject\n$body"
        if (!confirmWords.containsMatchIn(text) || cancelWords.containsMatchIn(subject)) return null
        val kind = when {
            hotelWords.containsMatchIn(text) && !trainWords.containsMatchIn(text) && !flightWords.containsMatchIn(text) -> TripKind.HOTEL
            flightWords.containsMatchIn(text) || flightNo.containsMatchIn(text) -> TripKind.FLIGHT
            trainWords.containsMatchIn(text) -> TripKind.TRAIN
            busWords.containsMatchIn(text) -> TripKind.BUS
            else -> return null
        }
        val date = dateLabel.find(text)?.groupValues?.get(1)?.let { ReceiptParser.parseDate(it.trim().trimEnd(',', '.'), received) } ?: return null
        // Old bookings are not trips to remind about.
        if (date.isBefore(received.minusDays(1))) return null
        val time = timeLabel.find(text)?.let { m -> clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) }
        val ref = pnr.find(text)?.groupValues?.get(1)?.uppercase()
        val (from, to) = (route.find(text) ?: arrowRoute.find(text))?.let { it.groupValues[1].trim() to it.groupValues[2].trim() } ?: (null to null)
        val title = when (kind) {
            TripKind.TRAIN -> trainTitle(text)
            TripKind.FLIGHT -> flightNo.find(text)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
            TripKind.HOTEL -> hotelName.find(text)?.groupValues?.get(1)?.trim()
            TripKind.BUS -> null
        } ?: ReceiptParser.merchantOf(sender)
        return TripFact(kind, title.take(MAX_TITLE), ref, date, time, from?.take(MAX_PLACE), to?.take(MAX_PLACE))
    }

    /** "12627 KARNATAKA EXP": the train number, with its name when the message gives one. */
    private fun trainTitle(text: String): String? {
        val m = trainNo.findAll(text).firstOrNull { it.groupValues[2].isNotBlank() } ?: trainNo.find(text) ?: return null
        return listOf(m.groupValues[1], m.groupValues[2].trim()).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun clock(h: Int, m: Int, ampm: String): LocalTime? {
        val hour = when (ampm.lowercase()) {
            "pm" -> if (h == NOON) NOON else h + NOON
            "am" -> if (h == NOON) 0 else h
            else -> h
        }
        return if (hour in 0..LAST_HOUR) LocalTime.of(hour, m) else null
    }

    private const val NOON = 12
    private const val LAST_HOUR = 23
    private const val MAX_TITLE = 80
    private const val MAX_PLACE = 40
}

/** What an order mail or SMS says about one order. */
data class OrderFact(val merchant: String, val orderRef: String, val status: OrderStatus, val expectedOn: LocalDate?, val returnBy: LocalDate?)

/** Online orders (placed, shipped, out for delivery, delivered, cancelled) from mail or a shared SMS, with rules only. */
object OrderParser {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val orderWords = Regex("\\b(order|shipment|package|parcel|delivery|delivered|dispatched|shipped)\\b", opt)
    private val skipWords = Regex("\\b(otp|offer|sale|discount|coupon|cashback|recommended|wishlist|review your)\\b", opt)
    private val ids = listOf(
        Regex("\\b(\\d{3}-\\d{7}-\\d{7})\\b"),
        Regex("\\b(OD\\d{12,20})\\b"),
        Regex("\\border\\s*(?:id|no\\.?|number|#)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9-]{5,24})\\b", opt),
    )
    private val expected = Regex(
        "(?:out for delivery|arriving|arrives|delivery by|deliver(?:ed)? by|expected (?:delivery|by)|estimated delivery|will be delivered (?:by|on))" +
            "\\s*[:-]?\\s*(?:on\\s*)?(?:[A-Za-z]{3,9},?\\s*)?" +
            "([0-9]{1,2}[ /\\-.][A-Za-z0-9]{1,9}[ /\\-.,]*[0-9]{2,4}|[A-Za-z]{3,9} [0-9]{1,2},? [0-9]{4}|[0-9]{1,2} [A-Za-z]{3,9}|today|tomorrow)",
        opt,
    )
    private val returnBy = Regex(
        "(?:return (?:window )?(?:closes|ends|is open till|available till|by|until)|returnable (?:till|until))\\s*(?:on\\s*)?[:-]?\\s*" +
            "([0-9]{1,2}[ /\\-.][A-Za-z0-9]{1,9}[ /\\-.,]*[0-9]{2,4}|[A-Za-z]{3,9} [0-9]{1,2},? [0-9]{4}|[0-9]{1,2} [A-Za-z]{3,9})",
        opt,
    )

    @Suppress("ReturnCount") // each early return rejects a message that is not an order update
    fun parse(sender: String, subject: String, body: String, received: LocalDate): OrderFact? {
        val text = "$subject\n$body"
        if (skipWords.containsMatchIn(subject) || !orderWords.containsMatchIn(text)) return null
        val ref = ids.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.uppercase() ?: return null
        val status = statusOf(text) ?: return null
        val eta = expected.find(text)?.groupValues?.get(1)?.let { day(it, received) }
        val ret = returnBy.find(text)?.groupValues?.get(1)?.let { day(it, received) }
        return OrderFact(ReceiptParser.merchantOf(sender), ref, status, eta, ret)
    }

    private fun statusOf(text: String): OrderStatus? {
        val t = text.lowercase()
        return when {
            "cancelled" in t || "canceled" in t -> OrderStatus.CANCELLED
            "out for delivery" in t -> OrderStatus.OUT_FOR_DELIVERY
            Regex("\\b(has been|was|been) delivered\\b|\\bdelivered\\b").containsMatchIn(t) && "will be delivered" !in t -> OrderStatus.DELIVERED
            Regex("\\b(shipped|dispatched|in transit|on its way|on the way)\\b").containsMatchIn(t) -> OrderStatus.SHIPPED
            Regex("\\b(order (placed|confirmed|received)|thank you for (your )?order|we.ve received your order)\\b").containsMatchIn(t) -> OrderStatus.ORDERED
            else -> null
        }
    }

    private fun day(s: String, received: LocalDate): LocalDate? = when (s.lowercase()) {
        "today" -> received
        "tomorrow" -> received.plusDays(1)
        else -> ReceiptParser.parseDate(s.trim().trimEnd(',', '.'), received)
    }
}

/** Turns a parsed booking into a stored Trip. A booking without a time starts at midnight (no "3 hours before" reminder). */
fun TripFact.toTrip(zone: java.time.ZoneId): app.assistant.domain.model.Trip {
    val start = (time?.let { date.atTime(it) } ?: date.atStartOfDay()).atZone(zone).toInstant()
    val key = ref ?: "$kind-$date-${title.lowercase().filter(Char::isLetterOrDigit).take(TRIP_KEY_CHARS)}"
    return app.assistant.domain.model.Trip(0, kind, title, ref, start, from, to, "trip:$key")
}

private const val TRIP_KEY_CHARS = 20
