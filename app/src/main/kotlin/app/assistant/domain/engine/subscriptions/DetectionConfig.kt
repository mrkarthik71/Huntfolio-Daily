package app.assistant.domain.engine.subscriptions

/**
 * Tunables for subscription detection and charge matching (Glossary: Amount_Tolerance, Charge_Window, Min_Occurrences).
 * Defaults are set here, per the design's "defaults set in the design" note.
 *
 * - [minOccurrences]: at least this many debits to the same Merchant form a Recurring_Pattern. 2 means a yearly pattern
 *   cannot be found from the 3-month import and must be added manually (Requirement 37, Assumptions). Default 3.
 * - [amountTolerancePercent]: a charge within this % of the latest charge amount counts as the same price. Default 10.
 * - [chargeWindowDays]: a debit within this many days of the expected date counts as that charge. Default 5.
 */
data class DetectionConfig(
    val minOccurrences: Int = DEFAULT_MIN_OCCURRENCES,
    val amountTolerancePercent: Int = DEFAULT_AMOUNT_TOLERANCE_PERCENT,
    val chargeWindowDays: Long = DEFAULT_CHARGE_WINDOW_DAYS,
) {
    init {
        require(minOccurrences >= MIN_ALLOWED_OCCURRENCES) { "Min_Occurrences is 2 or more" }
        require(amountTolerancePercent in 0..100) { "Amount_Tolerance is a percentage" }
        require(chargeWindowDays >= 0) { "Charge_Window is non-negative" }
    }

    companion object {
        const val MIN_ALLOWED_OCCURRENCES = 2
        const val DEFAULT_MIN_OCCURRENCES = 3
        const val DEFAULT_AMOUNT_TOLERANCE_PERCENT = 10
        const val DEFAULT_CHARGE_WINDOW_DAYS = 5L
    }
}
