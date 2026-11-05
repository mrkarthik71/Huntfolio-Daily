package app.assistant.ingest.sms

import app.assistant.domain.port.ReaderStateStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Sms_Reader cursor (Requirement 26.6), kept in the reader-state DataStore so it survives a reboot and is cleared by
 * the Data_Wiper with the rest of the settings. It never moves backwards: [ReaderStateStore.advanceSmsCursor] ignores a
 * value at or before the stored one.
 */
@Singleton
class ReaderStateSmsCursor @Inject constructor(private val state: ReaderStateStore) : SmsCursor {
    override suspend fun lastReceivedAt(): Instant? = state.current().smsCursor

    override suspend fun advanceTo(at: Instant) = state.advanceSmsCursor(at)
}
