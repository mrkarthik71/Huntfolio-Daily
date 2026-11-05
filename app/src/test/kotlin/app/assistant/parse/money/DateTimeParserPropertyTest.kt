// Feature: Phase 3 Money — Property 35: Date and time parsing round trip.
package app.assistant.parse.money

import app.assistant.domain.error.Outcome
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val zones = listOf(ZoneId.of("UTC"), ZoneId.of("Asia/Kolkata"))
private val separators = listOf("-", "/", ".", " ", "")

private data class DateForm(val order: DateOrder, val sep: String, val monthStyle: Int, val fourDigitYear: Boolean, val padded: Boolean)

private data class TimeForm(val twelveHour: Boolean, val seconds: Boolean, val padHour: Boolean, val spaceBeforeMeridiem: Boolean, val meridiemCase: Int)

private fun two(n: Int) = n.toString().padStart(2, '0')

/** A compact date (no separator) is always zero-padded so its width is fixed. */
private fun renderDate(d: LocalDate, f: DateForm): String {
    val pad = f.padded || f.sep.isEmpty()
    val day = if (pad) two(d.dayOfMonth) else d.dayOfMonth.toString()
    val month = if (f.monthStyle == 0) {
        if (pad) two(d.monthValue) else d.monthValue.toString()
    } else {
        val name = monthNames[d.monthValue - 1]
        when (f.monthStyle) {
            1 -> name.uppercase()
            2 -> name.lowercase()
            else -> name
        }
    }
    val year = if (f.fourDigitYear) d.year.toString() else two(d.year % 100)
    val parts = when (f.order) {
        DateOrder.DMY -> listOf(day, month, year)
        DateOrder.MDY -> listOf(month, day, year)
        DateOrder.YMD -> listOf(year, month, day)
    }
    return parts.joinToString(f.sep)
}

private fun renderTime(t: LocalTime, f: TimeForm): String {
    val seconds = if (f.seconds) ":${two(t.second)}" else ""
    if (!f.twelveHour) {
        val hour = if (f.padHour) two(t.hour) else t.hour.toString()
        return "$hour:${two(t.minute)}$seconds"
    }
    val hour12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    val hour = if (f.padHour) two(hour12) else hour12.toString()
    val meridiem = (if (t.hour < 12) "AM" else "PM").let {
        when (f.meridiemCase) {
            0 -> it
            1 -> it.lowercase()
            else -> it.take(1) + it.drop(1).lowercase()
        }
    }
    return "$hour:${two(t.minute)}$seconds${if (f.spaceBeforeMeridiem) " " else ""}$meridiem"
}

private val arbDateForm = arbitrary {
    DateForm(
        order = Arb.element(DateOrder.entries).bind(),
        sep = Arb.element(separators).bind(),
        monthStyle = Arb.int(0..3).bind(),
        fourDigitYear = Arb.boolean().bind(),
        padded = Arb.boolean().bind(),
    )
}

private val arbTimeForm = arbitrary {
    TimeForm(
        twelveHour = Arb.boolean().bind(),
        seconds = Arb.boolean().bind(),
        padHour = Arb.boolean().bind(),
        spaceBeforeMeridiem = Arb.boolean().bind(),
        meridiemCase = Arb.int(0..2).bind(),
    )
}

/** A local date and time with whole minutes, or seconds when the form shows them. */
private fun arbLocalDateTime(withSeconds: Boolean) = arbitrary {
    val date = LocalDate.of(2001, 1, 1).plusDays(Arb.long(0L..35_000L).bind())
    val second = if (withSeconds) Arb.int(0..59).bind() else 0
    LocalDateTime.of(date, LocalTime.of(Arb.int(0..23).bind(), Arb.int(0..59).bind(), second))
}

class DateTimeParserPropertyTest {

    @Test
    fun `Property 35 - rendering then parsing gives the same date and time`() = runTest {
        checkAll(arbLocalDateTime(withSeconds = true), arbDateForm, arbTimeForm, Arb.element(zones), Arb.long(0L..172_800L)) {
                dt,
                df,
                tf,
                zone,
                receivedAfterSeconds,
            ->
            val expected = dt.withSecond(if (tf.seconds) dt.second else 0)
            val receivedAt: Instant = expected.atZone(zone).toInstant().plusSeconds(receivedAfterSeconds)
            val result = DateTimeParser.parse(renderDate(expected.toLocalDate(), df), renderTime(expected.toLocalTime(), tf), df.order, receivedAt, zone)
            assertEquals(Outcome.Ok(expected), result, "date='${renderDate(expected.toLocalDate(), df)}' time='${renderTime(expected.toLocalTime(), tf)}'")
        }
    }

    @Test
    fun `a missing date or time is taken from the Received_Time`() = runTest {
        checkAll(arbLocalDateTime(withSeconds = false), Arb.element(zones), arbDateForm, arbTimeForm) { received, zone, df, tf ->
            val receivedAt = received.atZone(zone).toInstant()

            assertEquals(Outcome.Ok(received), DateTimeParser.parse(null, null, df.order, receivedAt, zone))

            // A date with no time keeps the Received_Time's time of day.
            val pastDate = received.toLocalDate().minusDays(10)
            assertEquals(
                Outcome.Ok(LocalDateTime.of(pastDate, received.toLocalTime())),
                DateTimeParser.parse(renderDate(pastDate, df), null, df.order, receivedAt, zone),
            )

            // A time with no date keeps the Received_Time's date.
            val anyTime = LocalTime.of((received.hour + 7) % 24, (received.minute + 11) % 60)
            assertEquals(
                Outcome.Ok(LocalDateTime.of(received.toLocalDate(), anyTime)),
                DateTimeParser.parse(null, renderTime(anyTime, tf.copy(seconds = false)), df.order, receivedAt, zone),
            )
        }
    }

    @Test
    fun `a date more than 1 day after the Received_Time is rejected unless a future date is allowed`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val received = LocalDateTime.of(2025, 1, 5, 14, 5).atZone(zone).toInstant()
        assertEquals(Outcome.Ok(LocalDateTime.of(2025, 1, 6, 9, 0)), DateTimeParser.parse("06-01-25", "09:00", DateOrder.DMY, received, zone))
        assertEquals(Outcome.Err(DateError.FUTURE_DATE), DateTimeParser.parse("07-01-25", "09:00", DateOrder.DMY, received, zone))
        assertEquals(
            Outcome.Ok(LocalDateTime.of(2025, 1, 7, 9, 0)),
            DateTimeParser.parse("07-01-25", "09:00", DateOrder.DMY, received, zone, allowFuture = true),
        )
    }

    @Test
    fun `the examples from the requirement`() {
        val zone = ZoneId.of("UTC")
        val received = LocalDateTime.of(2025, 1, 5, 20, 0).atZone(zone).toInstant()
        val at = { date: String, time: String? -> DateTimeParser.parse(date, time, DateOrder.DMY, received, zone) }
        listOf("05-01-25", "05/01/2025", "05-Jan-25", "05JAN2025", "05-jan-2025", "5.1.25", "050125", "05012025").forEach {
            assertEquals(Outcome.Ok(LocalDateTime.of(2025, 1, 5, 14, 5)), at(it, "14:05"), it)
        }
        assertEquals(Outcome.Ok(LocalDateTime.of(2025, 1, 5, 14, 5)), at("05-01-25", "02:05 PM"))
        assertEquals(Outcome.Ok(LocalDateTime.of(2025, 1, 5, 0, 5)), at("05-01-25", "12:05 AM"))
        assertEquals(Outcome.Ok(LocalDateTime.of(2025, 1, 5, 12, 5)), at("05-01-25", "12:05pm"))
        assertEquals(Outcome.Ok(LocalDateTime.of(2025, 1, 5, 14, 5, 33)), at("05-01-25", "14:05:33"))
    }

    @Test
    fun `other orders read the same date`() {
        val zone = ZoneId.of("UTC")
        val received = LocalDateTime.of(2025, 1, 5, 20, 0).atZone(zone).toInstant()
        val expected = Outcome.Ok(LocalDateTime.of(2025, 1, 5, 8, 0))
        assertEquals(expected, DateTimeParser.parse("01/05/2025", "08:00", DateOrder.MDY, received, zone))
        assertEquals(expected, DateTimeParser.parse("JAN052025", "08:00", DateOrder.MDY, received, zone))
        assertEquals(expected, DateTimeParser.parse("2025-01-05", "08:00", DateOrder.YMD, received, zone))
        assertEquals(expected, DateTimeParser.parse("25JAN05", "08:00", DateOrder.YMD, received, zone))
    }

    @Test
    fun `impossible or malformed dates and times are rejected`() {
        val zone = ZoneId.of("UTC")
        val received = LocalDateTime.of(2025, 3, 5, 20, 0).atZone(zone).toInstant()
        listOf("31-02-25", "32/01/25", "00-01-25", "05-13-25", "05-Foo-25", "5-1-2", "05-01", "05--01-25", "05-01/25", "").forEach {
            assertEquals(Outcome.Err(DateError.INVALID_DATE), DateTimeParser.parse(it, "10:00", DateOrder.DMY, received, zone), "date '$it'")
        }
        listOf("24:00", "25:00", "12:60", "13:00 PM", "00:30 AM", "0:30 PM", "10", "10:5", "ten", "").forEach {
            assertEquals(Outcome.Err(DateError.INVALID_TIME), DateTimeParser.parse("05-03-25", it, DateOrder.DMY, received, zone), "time '$it'")
        }
    }
}
