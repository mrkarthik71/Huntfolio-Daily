package app.assistant.ai.gateway

import app.assistant.domain.port.TimeSource
import java.time.Duration

/**
 * Opens after [maxFailures] consecutive failures and stays open for [openFor] (Requirement 43.4).
 * While open the gateway attempts nothing. A single success closes it and clears the count. The
 * clock comes from [TimeSource] so tests drive it deterministically.
 */
class CircuitBreaker(
    private val time: TimeSource,
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val openFor: Duration = DEFAULT_OPEN_FOR,
) {
    private var consecutiveFailures = 0
    private var openedAtMillis: Long? = null

    @Synchronized
    fun isOpen(): Boolean {
        val opened = openedAtMillis ?: return false
        if (time.now().toEpochMilli() - opened >= openFor.toMillis()) {
            // The cool-down elapsed: half-open — allow the next attempt to probe.
            openedAtMillis = null
            return false
        }
        return true
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openedAtMillis = null
    }

    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= maxFailures) {
            openedAtMillis = time.now().toEpochMilli()
            consecutiveFailures = 0
        }
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 3
        val DEFAULT_OPEN_FOR: Duration = Duration.ofMinutes(30)
    }
}
