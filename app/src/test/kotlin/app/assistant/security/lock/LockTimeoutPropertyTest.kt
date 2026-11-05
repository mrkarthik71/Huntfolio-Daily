package app.assistant.security.lock

import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Feature: personal-assistant-android, Property 7: Lock timeout policy.
 * Validates: Requirements 6.2.
 */
class LockTimeoutPropertyTest {

    @Test
    fun `re-lock happens exactly when time away is longer than the lock timeout`() = runTest {
        checkAll(
            PropertyDefaults.standard,
            Arb.long(0L..10_000_000_000L),
            Arb.long(-60_000L..3_600_000L),
            Arb.long(0L..1_800L),
        ) { backgroundedAt, away, timeoutSeconds ->
            val timeout = Duration.ofSeconds(timeoutSeconds)
            val now = backgroundedAt + away
            val expected = away < 0 || away > timeout.toMillis()
            assertEquals(expected, LockPolicy.requiresAuth(backgroundedAt, now, timeout))
        }
    }

    @Test
    fun `state machine starts locked and re-locks only after the timeout`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.long(0L..3_600_000L), Arb.long(0L..1_800L)) { away, timeoutSeconds ->
            val timeout = Duration.ofSeconds(timeoutSeconds)
            val m = LockStateMachine()
            assertEquals(LockState.LOCKED, m.state)
            m.onAuthStarted()
            m.onAuthResult(AuthResult.SUCCESS)
            m.onBackground(1_000L)
            m.onForeground(1_000L + away, timeout)
            val expected = if (away > timeout.toMillis()) LockState.LOCKED else LockState.UNLOCKED
            assertEquals(expected, m.state)
        }
    }

    @Test
    fun `failed or cancelled authentication keeps data hidden`() {
        for (r in listOf(AuthResult.FAILED, AuthResult.CANCELLED)) {
            val m = LockStateMachine()
            m.onAuthStarted()
            m.onAuthResult(r)
            assertEquals(LockState.LOCKED, m.state)
        }
        val m = LockStateMachine()
        m.onAuthResult(AuthResult.NO_CREDENTIAL)
        assertEquals(LockState.NO_DEVICE_LOCK, m.state)
    }
}
