package app.assistant.ai.gateway

import app.assistant.domain.port.CapStore
import app.assistant.domain.port.TimeSource
import java.time.format.DateTimeFormatter

/**
 * Enforces the AI_Monthly_Cap (Requirement 45, design: Cap_Governor). It reserves an estimated cost
 * BEFORE an attempt and records every attempt afterwards — including failures — so a run of failed
 * calls still counts against the budget and cannot be used to retry without limit (Requirement
 * 45.6). The month is the device-zone calendar month, so the budget resets naturally at rollover
 * (Requirement 45.7); no timer is needed because the key simply changes.
 */
class CapGovernor(
    private val store: CapStore,
    private val time: TimeSource,
    /** Flat estimated cost reserved per Cloud attempt, in paise. A real adapter can refine this later. */
    private val perCallPaise: Long = DEFAULT_PER_CALL_PAISE,
) {
    /** The current month key `YYYY-MM` in the device zone. */
    fun month(): String = time.today().format(MONTH)

    /**
     * Reserves one call against [capPaise]. Returns true when there is budget; false when the cap is
     * reached (the caller then shows the resume date and does not attempt). A cap of 0 means no cap
     * is configured, so Cloud is refused upstream and this is never reached with 0.
     */
    suspend fun tryReserve(capPaise: Long): Boolean {
        if (capPaise <= 0) return false
        return store.tryReserve(month(), perCallPaise, capPaise)
    }

    /** Records that an attempt happened (success or failure). Call once per attempt after it resolves. */
    suspend fun record(extraPaise: Long = 0) {
        store.recordAttempt(month(), extraPaise)
    }

    /** Paise spent this month. */
    suspend fun spent(): Long = store.spent(month())

    /** True when this month's spend has reached [capPaise] (no budget for another [perCallPaise] call). */
    suspend fun isCapReached(capPaise: Long): Boolean = capPaise > 0 && spent() + perCallPaise > capPaise

    /** The first day of next month in the device zone — shown to the user as the resume date (Requirement 45.4). */
    fun resumeDateIso(): String = time.today().withDayOfMonth(1).plusMonths(1).toString()

    companion object {
        /** Conservative flat estimate until a vendor adapter reports real token costs. */
        const val DEFAULT_PER_CALL_PAISE = 200L // ₹2 per call
        private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
