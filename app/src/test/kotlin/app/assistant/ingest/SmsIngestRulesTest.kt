// Feature: Phase 3 Money — Property 32: SMS history import start date; Property 33: Multipart SMS join.
package app.assistant.ingest

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SmsIngestRulesTest {

    /** The rule as the requirement states it, by plain month arithmetic, independent of `minusMonths`. */
    private fun referenceStart(enabledOn: LocalDate): LocalDate {
        val monthIndex = enabledOn.year * 12 + (enabledOn.monthValue - 1) - 3
        val year = Math.floorDiv(monthIndex, 12)
        val month = Math.floorMod(monthIndex, 12) + 1
        val lastDay = LocalDate.of(year, month, 1).lengthOfMonth()
        return LocalDate.of(year, month, minOf(enabledOn.dayOfMonth, lastDay))
    }

    @Test
    fun `Property 32 - the start is the same day three calendar months earlier, or that month's last day`() = runTest {
        checkAll(Arb.long(-20_000L..60_000L)) { epochDay ->
            val enabledOn = LocalDate.ofEpochDay(epochDay)
            assertEquals(referenceStart(enabledOn), SmsIngestRules.historyImportStart(enabledOn), "enabled on $enabledOn")
        }
    }

    @Test
    fun `the start date examples, including a day the earlier month does not have`() {
        fun start(y: Int, m: Int, d: Int) = SmsIngestRules.historyImportStart(LocalDate.of(y, m, d))
        assertEquals(LocalDate.of(2025, 1, 5), start(2025, 4, 5))
        assertEquals(LocalDate.of(2025, 2, 28), start(2025, 5, 31), "31 May: February has 28 days")
        assertEquals(LocalDate.of(2024, 2, 29), start(2024, 5, 31), "31 May in a leap year")
        assertEquals(LocalDate.of(2025, 2, 28), start(2025, 5, 30))
        assertEquals(LocalDate.of(2024, 11, 28), start(2025, 2, 28))
        assertEquals(LocalDate.of(2024, 12, 31), start(2025, 3, 31), "across a year boundary")
        assertEquals(LocalDate.of(2024, 9, 30), start(2024, 12, 30))
        assertEquals(LocalDate.of(2024, 9, 30), start(2024, 12, 31), "31 Dec: September has 30 days")
    }

    private fun split(text: String, cuts: List<Int>): List<String> {
        val points = (listOf(0) + cuts.map { it.coerceIn(0, text.length) }.sorted() + text.length)
        return points.zipWithNext { from, to -> text.substring(from, to) }
    }

    private val chars = "ab Rs12,.-/@\u20B9\uD83D\uDE00\n"

    @Test
    fun `Property 33 - parts delivered in any order join to the original text`() = runTest {
        val arbCase = arbitrary {
            val text = (1..Arb.int(0..400).bind()).map { chars[Arb.int(chars.indices).bind()] }.joinToString("")
            val partCount = Arb.int(0..12).bind()
            val cuts = (1..partCount).map { Arb.int(0..text.length).bind() }
            val parts = split(text, cuts)
            // A shuffled delivery order: sort by a random key per part.
            val keys = parts.indices.map { Arb.int(0..1_000_000).bind() }
            val delivered = parts.withIndex().sortedBy { keys[it.index] }.map { it.index to it.value }
            text to delivered
        }
        checkAll(arbCase) { (text, delivered) ->
            assertEquals(text, SmsIngestRules.joinParts(delivered))
        }
    }

    @Test
    fun `a single part joins to itself and an empty part is kept in place`() {
        assertEquals("only", SmsIngestRules.joinParts(listOf(0 to "only")))
        assertEquals("ab", SmsIngestRules.joinParts(listOf(2 to "b", 1 to "", 0 to "a")))
    }

    @Test
    fun `parts with a gap, a repeat or no parts are rejected instead of joining the wrong text`() {
        assertThrows(IllegalArgumentException::class.java) { SmsIngestRules.joinParts(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { SmsIngestRules.joinParts(listOf(0 to "a", 2 to "c")) }
        assertThrows(IllegalArgumentException::class.java) { SmsIngestRules.joinParts(listOf(0 to "a", 0 to "b")) }
        assertThrows(IllegalArgumentException::class.java) { SmsIngestRules.joinParts(listOf(1 to "a", 2 to "b")) }
        assertThrows(IllegalArgumentException::class.java) { SmsIngestRules.joinParts(listOf(-1 to "a", 0 to "b")) }
    }
}
