package app.assistant.domain.engine.schedule

import app.assistant.domain.model.DeviceTrigger
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.model.SmartReminder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 5)
    private val car = SmartReminder(ReminderId(1), "Call Mom", ReminderKind.DEVICE, device = DeviceTrigger.CAR_BLUETOOTH, notBefore = LocalTime.of(18, 0))

    @Test
    fun `a car reminder fires after its time, once a day, only for its trigger`() {
        assertFalse(ReminderRules.deviceFires(car, DeviceTrigger.CAR_BLUETOOTH, LocalTime.of(17, 59), today, null, zone))
        assertTrue(ReminderRules.deviceFires(car, DeviceTrigger.CAR_BLUETOOTH, LocalTime.of(18, 5), today, null, zone))
        assertFalse(ReminderRules.deviceFires(car, DeviceTrigger.CHARGING, LocalTime.of(18, 5), today, null, zone))
        val firedToday = today.atTime(18, 6).atZone(zone).toInstant()
        assertFalse(ReminderRules.deviceFires(car, DeviceTrigger.CAR_BLUETOOTH, LocalTime.of(19, 0), today, firedToday, zone))
        assertTrue(ReminderRules.deviceFires(car, DeviceTrigger.CAR_BLUETOOTH, LocalTime.of(18, 30), today.plusDays(1), firedToday, zone))
        assertFalse(ReminderRules.deviceFires(car.copy(active = false), DeviceTrigger.CAR_BLUETOOTH, LocalTime.of(19, 0), today, null, zone))
    }

    @Test
    fun `a place reminder matches its transition and waits an hour between fires`() {
        val place = SmartReminder(ReminderId(2), "Courier", ReminderKind.PLACE, onEnter = true, latitude = 1.0, longitude = 1.0, radiusMeters = 200)
        val now = Instant.parse("2026-11-05T05:00:00Z")
        assertTrue(ReminderRules.placeFires(place, entering = true, now = now, lastFired = null))
        assertFalse(ReminderRules.placeFires(place, entering = false, now = now, lastFired = null))
        assertFalse(ReminderRules.placeFires(place, entering = true, now = now, lastFired = now.minusSeconds(600)))
        assertTrue(ReminderRules.placeFires(place, entering = true, now = now, lastFired = now.minusSeconds(4_000)))
    }

    @Test
    fun `a time reminder is due once its time has come and only once`() {
        val at = Instant.parse("2026-11-05T03:30:00Z")
        val r = SmartReminder(ReminderId(3), "Pay rent", ReminderKind.TIME, fireAt = at)
        assertFalse(ReminderRules.timeDue(r, at.minusSeconds(60), null))
        assertTrue(ReminderRules.timeDue(r, at.plusSeconds(1), null))
        assertFalse(ReminderRules.timeDue(r, at.plusSeconds(1), at))
    }
}
