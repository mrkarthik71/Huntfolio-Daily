package app.assistant.domain.model

/**
 * Goal targets and progress, stored exactly as hundredths (0.01 is 1). The valid range for
 * targets and logged amounts is 0.01 to 999,999,999.99 (Requirement 11.1, 11.8).
 * Accumulated progress may grow past the maximum of a single entry.
 */
@JvmInline
value class Quantity(val hundredths: Long) : Comparable<Quantity> {
    operator fun plus(other: Quantity): Quantity = Quantity(Math.addExact(hundredths, other.hundredths))

    override fun compareTo(other: Quantity): Int = hundredths.compareTo(other.hundredths)

    val isValidEntry: Boolean get() = hundredths in MIN_VALID.hundredths..MAX_VALID.hundredths

    companion object {
        val ZERO = Quantity(0)
        val ONE = Quantity(100)
        val MIN_VALID = Quantity(1)
        val MAX_VALID = Quantity(99_999_999_999)
    }
}
