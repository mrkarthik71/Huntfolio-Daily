package app.assistant.domain.port

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The only way the app reads the clock and the device time zone (design: "Explicit time").
 * Engines receive `now` and `zone` as values; tests use a fake with a fixed clock.
 */
interface TimeSource {
    fun now(): Instant

    /** Read fresh on each call so it follows device time-zone changes. */
    fun zone(): ZoneId

    fun today(): LocalDate = now().atZone(zone()).toLocalDate()

    /** Emits the new zone when the device time zone or time changes (TIMEZONE_CHANGED, TIME_SET). */
    val zoneChanges: Flow<ZoneId>
}
