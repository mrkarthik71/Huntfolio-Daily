package app.assistant.ingest.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** What a WorkManager job for the Sms_Reader may carry: names and a date, never content (Requirements 3.2, 26.6). */
class SmsWorkInputTest {
    @Test
    fun `an input survives the trip through the map WorkManager stores`() {
        val catchUp = SmsWorkInput(SmsWorkKind.CATCH_UP)
        val import = SmsWorkInput(SmsWorkKind.HISTORY_IMPORT, LocalDate.of(2026, 11, 4))
        assertEquals(catchUp, SmsWorkInput.from(catchUp.toMap()))
        assertEquals(import, SmsWorkInput.from(import.toMap()))
    }

    @Test
    fun `the stored values are enum names and an ISO date only`() {
        val shape = Regex("^([A-Z_]+|\\d{4}-\\d{2}-\\d{2})$")
        val maps = listOf(SmsWorkInput(SmsWorkKind.CATCH_UP).toMap(), SmsWorkInput(SmsWorkKind.HISTORY_IMPORT, LocalDate.of(2026, 11, 4)).toMap())
        maps.forEach { map ->
            assertEquals(setOf("kind", "enabled_on").intersect(map.keys), map.keys)
            map.values.forEach { assertTrue(shape.matches(it), it) }
        }
    }

    @Test
    fun `data that this class did not write is refused`() {
        assertNull(SmsWorkInput.from(emptyMap()))
        assertNull(SmsWorkInput.from(mapOf("kind" to "WIPE_EVERYTHING")))
        assertNull(SmsWorkInput.from(mapOf("kind" to "catch_up")))
        // An import needs its date, and the date must be one.
        assertNull(SmsWorkInput.from(mapOf("kind" to "HISTORY_IMPORT")))
        assertNull(SmsWorkInput.from(mapOf("kind" to "HISTORY_IMPORT", "enabled_on" to "not a date")))
        assertNull(SmsWorkInput.from(mapOf("kind" to "CATCH_UP", "enabled_on" to "2026-13-45")))
    }

    @Test
    fun `every kind can be written and read back`() {
        SmsWorkKind.entries.forEach { kind ->
            val input = SmsWorkInput(kind, LocalDate.of(2026, 1, 31))
            assertEquals(input, SmsWorkInput.from(input.toMap()))
        }
    }
}
