package app.assistant.domain.engine.schedule

import kotlin.math.roundToInt

/** One finished task: what the User estimated and the minutes they actually spent on it in focus sessions. */
data class PaceSample(val estimateMinutes: Int, val actualMinutes: Int)

/**
 * Learns how long the User's tasks really take compared with their estimates, from focus sessions on
 * finished tasks. The factor is the median of actual ÷ estimate, needs [MIN_SAMPLES] tasks, and is
 * kept between [MIN_FACTOR] and [MAX_FACTOR] so one odd day cannot distort plans.
 */
object PaceRules {
    const val MIN_SAMPLES = 3
    const val MIN_FACTOR = 0.5
    const val MAX_FACTOR = 2.5
    private const val TINY = 5
    private const val PERCENT = 100

    /** Null until there is enough to learn from. */
    fun factor(samples: List<PaceSample>): Double? {
        val ratios = samples.filter { it.estimateMinutes >= TINY && it.actualMinutes >= TINY }
            .map { it.actualMinutes.toDouble() / it.estimateMinutes }
            .sorted()
        if (ratios.size < MIN_SAMPLES) return null
        val mid = ratios.size / 2
        val median = if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
        return median.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** An estimate adjusted by the factor, to the planner's 15-minute slots. */
    fun adjust(minutes: Int, factor: Double?): Int {
        if (factor == null) return minutes
        val raw = (minutes * factor).roundToInt()
        return ((raw + HorizonPlanner.SLOT - 1) / HorizonPlanner.SLOT * HorizonPlanner.SLOT).coerceAtLeast(HorizonPlanner.SLOT)
    }

    /** 1.3 → 130, for "your tasks take about 130% of your estimates". */
    fun percent(factor: Double): Int = (factor * PERCENT).roundToInt()
}
