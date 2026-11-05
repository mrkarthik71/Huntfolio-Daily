package app.assistant.ingest.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderStatusTest {
    @Test
    fun `notification reading is off whenever it is not switched on`() {
        listOf(true, false).forEach { granted ->
            listOf(true, false).forEach { connected ->
                assertEquals(ReaderState.OFF, ReaderStatus.notifications(enabled = false, accessGranted = granted, listenerConnected = connected))
            }
        }
    }

    @Test
    fun `switched on with access and a live listener it is active`() {
        assertEquals(ReaderState.ACTIVE, ReaderStatus.notifications(enabled = true, accessGranted = true, listenerConnected = true))
    }

    @Test
    fun `switched on but access lost or the listener dropped it is disconnected`() {
        assertEquals(ReaderState.DISCONNECTED, ReaderStatus.notifications(enabled = true, accessGranted = true, listenerConnected = false))
        assertEquals(ReaderState.DISCONNECTED, ReaderStatus.notifications(enabled = true, accessGranted = false, listenerConnected = true))
        assertEquals(ReaderState.DISCONNECTED, ReaderStatus.notifications(enabled = true, accessGranted = false, listenerConnected = false))
    }

    @Test
    fun `coming back from Android's settings without granting access keeps reading off`() {
        assertEquals(false, ReaderStatus.notificationsEnabledAfterSettings(accessGranted = false))
        assertEquals(true, ReaderStatus.notificationsEnabledAfterSettings(accessGranted = true))
    }
}
