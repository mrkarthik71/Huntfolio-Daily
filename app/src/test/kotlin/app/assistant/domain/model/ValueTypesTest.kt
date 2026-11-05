package app.assistant.domain.model

import app.assistant.domain.format.MoneyFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class ValueTypesTest {

    // --- Paise (Requirement 28.2, Glossary Valid_Amount) ---

    @Test
    fun `valid amount bounds are one paisa and 99,99,99,999 rupees 99 paise`() {
        assertFalse(Paise.isValid(Paise(0)))
        assertTrue(Paise.isValid(Paise(1)))
        assertTrue(Paise.isValid(Paise(99_999_999_999)))
        assertFalse(Paise.isValid(Paise(100_000_000_000)))
    }

    @Test
    fun `negative paise are rejected`() {
        assertThrows<IllegalArgumentException> { Paise(-1) }
    }

    @Test
    fun `addition overflow throws instead of wrapping`() {
        assertThrows<ArithmeticException> { Paise(Long.MAX_VALUE) + Paise(1) }
    }

    @Test
    fun `subtraction gives signed paise`() {
        assertEquals(SignedPaise(-500), Paise(1_000) - Paise(1_500))
        assertEquals(Paise(500), (Paise(1_000) - Paise(1_500)).magnitude())
    }

    @Test
    fun `sum of an empty list is zero`() {
        assertEquals(Paise.ZERO, Paise.sum(emptyList()))
        assertEquals(Paise(600), Paise.sum(listOf(Paise(100), Paise(200), Paise(300))))
    }

    // --- InstantRange (Requirement 23.3, Glossary Overlap) ---

    private val t0 = Instant.parse("2026-11-03T04:00:00Z")
    private fun range(fromMin: Long, toMin: Long) = InstantRange(t0.plus(Duration.ofMinutes(fromMin)), t0.plus(Duration.ofMinutes(toMin)))

    @Test
    fun `touching ranges do not overlap`() {
        assertFalse(range(0, 60).overlaps(range(60, 120)))
        assertFalse(range(60, 120).overlaps(range(0, 60)))
    }

    @Test
    fun `nested and partly shared ranges overlap`() {
        assertTrue(range(0, 120).overlaps(range(30, 60)))
        assertTrue(range(30, 60).overlaps(range(0, 120)))
        assertTrue(range(0, 60).overlaps(range(59, 90)))
    }

    @Test
    fun `an empty range overlaps nothing`() {
        assertFalse(range(30, 30).overlaps(range(0, 60)))
    }

    @Test
    fun `end before start is rejected`() {
        assertThrows<IllegalArgumentException> { range(60, 0) }
    }

    @Test
    fun `intersect returns the shared part or null`() {
        assertEquals(range(30, 60), range(0, 60).intersect(range(30, 90)))
        assertNull(range(0, 60).intersect(range(60, 90)))
    }

    // --- LocalTimeRange ---

    @Test
    fun `quiet hours over midnight contain late evening and early morning`() {
        val quiet = LocalTimeRange(LocalTime.of(22, 30), LocalTime.of(7, 0))
        assertTrue(quiet.spansMidnight)
        assertTrue(LocalTime.of(23, 0) in quiet)
        assertTrue(LocalTime.of(0, 0) in quiet)
        assertTrue(LocalTime.of(6, 59) in quiet)
        assertFalse(LocalTime.of(7, 0) in quiet)
        assertFalse(LocalTime.of(12, 0) in quiet)
        assertEquals(Duration.ofMinutes(510), quiet.duration)
    }

    @Test
    fun `daytime range is half open`() {
        val work = LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))
        assertFalse(work.spansMidnight)
        assertTrue(LocalTime.of(9, 0) in work)
        assertFalse(LocalTime.of(18, 0) in work)
        assertEquals(Duration.ofHours(9), work.duration)
    }

    @Test
    fun `equal start and end is empty`() {
        val empty = LocalTimeRange(LocalTime.NOON, LocalTime.NOON)
        assertTrue(empty.isEmpty)
        assertFalse(LocalTime.NOON in empty)
    }

    // --- SourceRef ---

    @Test
    fun `source ref knows its data source and hides its value in toString`() {
        val ref = SourceRef("sms:v1:ABCDEFGHIJKLMNOP")
        assertEquals(DataSource.SMS, ref.source)
        assertFalse(ref.toString().contains("ABCDEF"))
    }

    @Test
    fun `source ref with unknown prefix is rejected`() {
        assertThrows<IllegalArgumentException> { SourceRef("xyz:v1:AAAA") }
    }

    // --- MoneyFormatter (Indian grouping) ---

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "0 | ₹0",
            "1 | ₹0.01",
            "14900 | ₹149",
            "14950 | ₹149.50",
            "178800 | ₹1,788",
            "12345678 | ₹1,23,456.78",
            "99999999999 | ₹99,99,99,999.99",
            "10000000000 | ₹10,00,00,000",
        ],
    )
    fun `formats with indian grouping`(paise: Long, expected: String) {
        assertEquals(expected, MoneyFormatter.format(Paise(paise)))
    }

    @Test
    fun `always mode shows two decimals`() {
        assertEquals("₹1,788.00", MoneyFormatter.format(Paise(178_800), MoneyFormatter.Paisa.ALWAYS))
    }

    @Test
    fun `negative signed amounts get a minus sign`() {
        assertEquals("-₹500", MoneyFormatter.format(SignedPaise(-50_000)))
        assertEquals("₹500", MoneyFormatter.format(SignedPaise(50_000)))
    }
}
