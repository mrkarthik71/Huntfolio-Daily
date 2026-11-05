package app.assistant.ui.permission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Requirements 5.1, 5.5, 5.6, 5.7. */
class PermissionLogicTest {

    @Test
    fun `a feature never requested is not requested, even if the system would show the dialog`() {
        assertEquals(PermissionStatus.NOT_REQUESTED, PermissionLogic.status(granted = false, requestedBefore = false, systemWillShowDialog = true))
        assertEquals(PermissionStatus.NOT_REQUESTED, PermissionLogic.status(granted = false, requestedBefore = false, systemWillShowDialog = false))
    }

    @Test
    fun `denied once can be asked again`() {
        assertEquals(PermissionStatus.DENIED, PermissionLogic.status(granted = false, requestedBefore = true, systemWillShowDialog = true))
    }

    @Test
    fun `denied and no longer shown means the app settings page is needed`() {
        assertEquals(PermissionStatus.PERMANENTLY_DENIED, PermissionLogic.status(granted = false, requestedBefore = true, systemWillShowDialog = false))
    }

    @Test
    fun `granted wins`() {
        assertEquals(PermissionStatus.GRANTED, PermissionLogic.status(granted = true, requestedBefore = false, systemWillShowDialog = false))
    }

    @Test
    fun `revocations are features granted before and not now`() {
        assertEquals(
            setOf(Feature.CALENDAR),
            PermissionLogic.revoked(setOf(Feature.CALENDAR, Feature.NOTIFICATIONS), setOf(Feature.NOTIFICATIONS, Feature.SMS)),
        )
    }
}
