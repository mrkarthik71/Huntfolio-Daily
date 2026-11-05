package app.assistant.data.settings

import app.assistant.domain.engine.settings.SettingsField
import app.assistant.domain.engine.settings.SettingsValidator
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.StreakRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

class SettingsTest {

    @Test
    fun `defaults are valid and start with no notification type enabled`() {
        val d = AppSettings()
        assertTrue(SettingsValidator.validate(d).isValid)
        assertTrue(d.enabledTypes.isEmpty())
        assertEquals(8, d.dailyCap)
        assertEquals(8, d.limitFor(NotificationType.EVENT_REMINDER))
        assertEquals(3, d.limitFor(NotificationType.BILL_DUE))
    }

    @Test
    fun `proto round trip keeps every field`() {
        val s = AppSettings(
            lockTimeout = Duration.ofMinutes(5),
            morningBrief = LocalTime.of(6, 45),
            eveningReview = LocalTime.of(22, 15),
            workingHours = LocalTimeRange(LocalTime.of(10, 0), LocalTime.of(19, 30)),
            quietHours = null,
            dailyCap = 12,
            perTypeLimit = AppSettings.defaultPerTypeLimits() + (NotificationType.GOAL_NUDGE to 1),
            capExemptTypes = setOf(NotificationType.EVENT_REMINDER),
            enabledTypes = setOf(NotificationType.MORNING_BRIEF, NotificationType.EVENING_REVIEW),
            lastDayOfWeek = DayOfWeek.SATURDAY,
            streakRule = StreakRule.ANY_TASK_DONE,
        )
        assertEquals(s, SettingsMapper.toDomain(SettingsMapper.toProto(s)))
    }

    @Test
    fun `empty file reads as defaults`() {
        assertEquals(AppSettings(), SettingsMapper.toDomain(app.assistant.data.settings.proto.SettingsProto.getDefaultInstance()))
    }

    @Test
    fun `quiet hours over midnight are allowed but working hours over midnight are not`() {
        val overnight = LocalTimeRange(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertTrue(SettingsValidator.validate(AppSettings(quietHours = overnight)).isValid)
        assertEquals(
            setOf(SettingsField.WORKING_HOURS),
            SettingsValidator.validate(AppSettings(workingHours = overnight)).errors.keys,
        )
    }

    @Test
    fun `out of range values are rejected`() {
        val errors = SettingsValidator.validate(
            AppSettings(
                lockTimeout = Duration.ofMinutes(31),
                dailyCap = 0,
                perTypeLimit = mapOf(NotificationType.BILL_DUE to 21),
            ),
        )
        assertEquals(
            setOf(SettingsField.LOCK_TIMEOUT, SettingsField.DAILY_CAP, SettingsField.PER_TYPE_LIMIT),
            errors.errors.keys,
        )
        assertNull(SettingsValidator.validate(AppSettings(dailyCap = 20))[SettingsField.DAILY_CAP])
    }
}
