package app.assistant.domain.port

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The persisted, non-personal state of the money readers (Requirements 26.2, 26.6, 27): the switches, the one-time
 * history-import offer flag, and the Received_Time the Sms_Reader last processed. It holds no Message_Text and names no
 * sender, so it lives with the settings, outside the Encrypted_Store. The Android readers and the money settings drive it
 * through this port; it is backed by its own DataStore so a non-withSms build can still read the notification parts.
 */
data class ReaderFlags(
    val smsEnabled: Boolean = false,
    val smsEnabledAt: Instant? = null,
    val smsCursor: Instant? = null,
    val historyImportOffered: Boolean = false,
    val notificationsEnabled: Boolean = false,
)

/** Reads and updates the [ReaderFlags]. Each setter writes once; the [SmsCursor] view is the subset the reader core needs. */
interface ReaderStateStore {
    val flags: Flow<ReaderFlags>

    suspend fun current(): ReaderFlags

    /** Switches SMS reading on, recording [at] as the "enabled at" instant (Requirement 26.6). */
    suspend fun enableSms(at: Instant)

    suspend fun disableSms()

    /** Moves the SMS cursor forward only; a value not after the stored one is ignored. */
    suspend fun advanceSmsCursor(to: Instant)

    suspend fun markHistoryImportOffered()

    suspend fun setNotificationsEnabled(enabled: Boolean)
}
