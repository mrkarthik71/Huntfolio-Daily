package app.assistant.domain.model

/**
 * A non-negative amount of money in paise (1 rupee = 100 paise). Money is never a floating
 * point number anywhere in the app (ADR 0004). The sign of a money movement comes from
 * [TxnType], never from the amount.
 */
@JvmInline
value class Paise(val value: Long) : Comparable<Paise> {
    init {
        require(value >= 0) { "Paise cannot be negative" }
    }

    /** Throws ArithmeticException on overflow instead of wrapping. */
    operator fun plus(other: Paise): Paise = Paise(Math.addExact(value, other.value))

    operator fun minus(other: Paise): SignedPaise = SignedPaise(Math.subtractExact(value, other.value))

    operator fun times(count: Int): Paise = Paise(Math.multiplyExact(value, count.toLong()))

    override fun compareTo(other: Paise): Int = value.compareTo(other.value)

    val isValid: Boolean get() = isValid(this)

    companion object {
        val ZERO = Paise(0)

        /** ₹0.01, the smallest Valid_Amount. */
        val MIN_VALID = Paise(1)

        /** ₹99,99,99,999.99, the largest Valid_Amount. */
        val MAX_VALID = Paise(99_999_999_999)

        /** Glossary: Valid_Amount, ₹0.01 to ₹99,99,99,999.99 inclusive. */
        fun isValid(p: Paise): Boolean = p.value in MIN_VALID.value..MAX_VALID.value

        /** Sum with overflow checking; an empty list sums to zero. */
        fun sum(amounts: Iterable<Paise>): Paise = amounts.fold(ZERO) { acc, p -> acc + p }

        /** Adds two amounts; throws ArithmeticException on overflow. */
        fun addExact(a: Paise, b: Paise): Paise = a + b
    }
}

/** A money difference that may be negative: remaining budget, month-on-month change. */
@JvmInline
value class SignedPaise(val value: Long) : Comparable<SignedPaise> {
    val isNegative: Boolean get() = value < 0

    operator fun plus(other: SignedPaise): SignedPaise = SignedPaise(Math.addExact(value, other.value))

    operator fun unaryMinus(): SignedPaise = SignedPaise(Math.negateExact(value))

    override fun compareTo(other: SignedPaise): Int = value.compareTo(other.value)

    /** The size of the difference as a non-negative amount. */
    fun magnitude(): Paise = Paise(Math.absExact(value))
}
