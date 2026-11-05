package app.assistant.domain.port

/**
 * The persistent side of the Cap_Governor (Requirement 45, design: Cap_Governor). The governor's
 * decision logic lives in the ai layer; this port is its storage, implemented over the `ai_usage`
 * table. [tryReserve] is atomic: two callers cannot both pass the same remaining budget.
 */
interface CapStore {
    /**
     * Adds [paise] to [month]'s total only if the result stays within [capPaise]. Returns true when
     * reserved, false when it would exceed the cap. The month key is `YYYY-MM` in the device zone.
     */
    suspend fun tryReserve(month: String, paise: Long, capPaise: Long): Boolean

    /** Records one attempt (success or failure), optionally adding extra spend beyond the reservation. */
    suspend fun recordAttempt(month: String, extraPaise: Long = 0)

    /** Paise spent so far in [month]. */
    suspend fun spent(month: String): Long

    /** Attempts made so far in [month]. */
    suspend fun attempts(month: String): Int
}
