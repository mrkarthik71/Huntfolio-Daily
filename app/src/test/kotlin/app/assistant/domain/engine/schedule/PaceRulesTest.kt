package app.assistant.domain.engine.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceRulesTest {
    @Test
    fun needsThreeRealSamples() {
        assertNull(PaceRules.factor(listOf(PaceSample(30, 45), PaceSample(60, 90))))
        assertNull(PaceRules.factor(listOf(PaceSample(30, 45), PaceSample(60, 90), PaceSample(2, 40))))
    }

    @Test
    fun usesTheMedianSoOneOddTaskDoesNotSkewIt() {
        val f = PaceRules.factor(listOf(PaceSample(30, 45), PaceSample(60, 90), PaceSample(30, 300)))
        assertEquals(1.5, f!!, 0.0001)
    }

    @Test
    fun isClampedAndAdjustsToSlots() {
        assertEquals(2.5, PaceRules.factor(listOf(PaceSample(10, 100), PaceSample(10, 100), PaceSample(10, 100)))!!, 0.0001)
        assertEquals(45, PaceRules.adjust(30, 1.3))
        assertEquals(30, PaceRules.adjust(30, null))
        assertEquals(130, PaceRules.percent(1.3))
    }
}
