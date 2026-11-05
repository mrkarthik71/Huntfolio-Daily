package app.assistant.testing

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Proves that JUnit 5 and kotest-property run in the unit test task. */
class ToolchainSmokeTest {
    @Test
    fun `junit 5 runs`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `kotest property runs with the project default iterations`() = runTest {
        var cases = 0
        checkAll(PropertyDefaults.standard, Arb.long(-1_000_000L..1_000_000L)) { n ->
            cases++
            assertEquals(n, n + 0)
        }
        assertEquals(PropertyDefaults.standard.iterations, cases)
    }
}
