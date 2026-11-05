package app.assistant.ingest.sms

import app.assistant.ingest.SmsIngestRules
import app.assistant.ingest.notifications.ReaderState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The decisions of the Sms_Reader that need no phone: where to start reading, whether to offer the import, what state it is in. */
object SmsReaderRules {
    /**
     * Where catch-up starts after the process was stopped (Requirement 26.6): the Received_Time of the last SMS processed,
     * but never before the moment the User last switched SMS reading on, so turning it off and on again does not read what
     * arrived while it was off. The start is inclusive; an SMS read twice is harmless because ingest is idempotent (Requirement
     * 29.3), while one missed at a shared timestamp would be lost. Null means nothing to catch up: reading is not enabled.
     */
    fun catchUpSince(lastReceivedAt: Instant?, enabledAt: Instant?): Instant? = when {
        enabledAt == null -> null
        lastReceivedAt == null -> enabledAt
        else -> maxOf(lastReceivedAt, enabledAt)
    }

    /** The first instant the SMS_History_Import covers, in the device's zone (Requirement 26.2). */
    fun historyImportStart(enabledOn: LocalDate, zone: ZoneId): Instant = SmsIngestRules.historyImportStart(enabledOn).atStartOfDay(zone).toInstant()

    /** The import is offered once, when SMS reading is on and the permission is granted (Requirement 26.2). */
    fun shouldOfferHistoryImport(alreadyOffered: Boolean, enabled: Boolean, permissionGranted: Boolean): Boolean =
        enabled && permissionGranted && !alreadyOffered

    fun state(enabled: Boolean, permissionGranted: Boolean): ReaderState = when {
        !enabled -> ReaderState.OFF
        !permissionGranted -> ReaderState.NEEDS_PERMISSION
        else -> ReaderState.ACTIVE
    }
}
