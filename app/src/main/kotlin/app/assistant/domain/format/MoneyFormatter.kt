package app.assistant.domain.format

import app.assistant.domain.model.Paise
import app.assistant.domain.model.SignedPaise

/**
 * Formats money for display with Indian digit grouping: the last three digits, then groups
 * of two (₹1,23,456.78). Digits are always ASCII, whatever the App_Language (Requirement 72.5).
 */
object MoneyFormatter {
    private const val RUPEE = "₹"

    enum class Paisa {
        /** Always show two decimals: ₹1,788.00. */
        ALWAYS,

        /** Show decimals only when the paise part is not zero: ₹1,788 and ₹149.50. */
        WHEN_NONZERO,
    }

    fun format(amount: Paise, paisa: Paisa = Paisa.WHEN_NONZERO): String = RUPEE + digits(amount.value, paisa)

    /** Negative values get a leading minus sign: -₹500. */
    fun format(amount: SignedPaise, paisa: Paisa = Paisa.WHEN_NONZERO): String {
        val sign = if (amount.isNegative) "-" else ""
        return sign + RUPEE + digits(amount.magnitude().value, paisa)
    }

    /** Groups whole rupees the Indian way, for example 12345678 → "1,23,45,678". */
    fun groupIndian(rupees: Long): String {
        require(rupees >= 0)
        val s = rupees.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3)
        val groups = rest.reversed().chunked(2).map { it.reversed() }.reversed()
        return groups.joinToString(",") + "," + last3
    }

    private fun digits(paise: Long, paisa: Paisa): String {
        val rupees = paise / 100
        val fraction = (paise % 100).toInt()
        val whole = groupIndian(rupees)
        return if (paisa == Paisa.ALWAYS || fraction != 0) {
            whole + "." + fraction.toString().padStart(2, '0')
        } else {
            whole
        }
    }
}
