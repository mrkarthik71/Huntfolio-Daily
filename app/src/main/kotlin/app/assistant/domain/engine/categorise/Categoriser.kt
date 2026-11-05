package app.assistant.domain.engine.categorise

import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Merchant
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.TransactionId
import java.util.Locale

/** A Transaction as the Categoriser sees it when a correction is applied to earlier ones (Requirement 31.6). */
data class CategorisedTransaction(
    val id: TransactionId,
    val merchantId: MerchantId?,
    val category: CategoryId,
    /** True when the User set this Transaction's category individually; a correction never overrides it. */
    val categoryUserSet: Boolean,
)

/**
 * Matches Transactions to Merchants and picks categories (Requirement 31, design: Categoriser).
 * Pure; the Merchant list is data, kept apart from this code.
 */
object Categoriser {

    /** Names and VPAs are compared ignoring letter case and leading and trailing space (Requirement 31.2). */
    fun normalise(text: String): String = text.trim().lowercase(Locale.ROOT)

    /**
     * The Merchant whose name or any alias equals [nameOrVpa] after normalising both, or null.
     * A blank [nameOrVpa] matches nothing. Matching is exact on the whole text, never a substring.
     */
    fun matchMerchant(nameOrVpa: String, merchants: List<Merchant>): Merchant? {
        val key = normalise(nameOrVpa)
        return if (key.isEmpty()) null else merchants.firstOrNull { m -> normalise(m.name) == key || m.aliases.any { normalise(it) == key } }
    }

    /** The User's latest correction for the Merchant, else its default category, else Uncategorised (Requirement 31.1). */
    fun categoryFor(merchant: Merchant?, latestCorrection: CategoryId?, uncategorised: CategoryId = CategoryId.UNCATEGORISED): CategoryId =
        latestCorrection ?: merchant?.defaultCategory ?: uncategorised

    /**
     * A new Merchant for a non-empty name or VPA that matches no Merchant: that text is both its
     * name and its alias, and it has no default category (Requirement 31.7). Null for blank text.
     */
    fun newMerchant(nameOrVpa: String): Merchant? =
        nameOrVpa.trim().takeIf { it.isNotEmpty() }?.let { Merchant(id = null, name = it, aliases = listOf(it), defaultCategory = null) }

    /**
     * The earlier Transactions after the User accepts a new category for [merchant]: every one of
     * that Merchant's Transactions takes [category] except those whose category the User set
     * individually (Requirement 31.6). Other Merchants' Transactions are returned unchanged.
     */
    fun applyCorrectionToEarlier(earlier: List<CategorisedTransaction>, merchant: MerchantId, category: CategoryId): List<CategorisedTransaction> =
        earlier.map { if (it.merchantId == merchant && !it.categoryUserSet) it.copy(category = category) else it }
}
