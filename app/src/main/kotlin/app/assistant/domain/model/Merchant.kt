package app.assistant.domain.model

/**
 * A payee with a name, aliases (including VPAs) and a default category (Glossary: Merchant).
 * [id] is null until the Merchant is stored.
 */
data class Merchant(val id: MerchantId?, val name: String, val aliases: List<String>, val defaultCategory: CategoryId?)

/**
 * The categories shipped with the app. A category's id is its position here plus one, so
 * `Uncategorised` is [CategoryId.UNCATEGORISED]; seeding must keep this order (design: category table).
 */
object BuiltinCategories {
    const val UNCATEGORISED_NAME = "Uncategorised"

    val NAMES: List<String> = listOf(
        UNCATEGORISED_NAME,
        "Food",
        "Groceries",
        "Shopping",
        "Transport",
        "Travel",
        "Fuel",
        "Subscriptions",
        "Bills",
        "Entertainment",
        "Health",
    )

    fun idOf(name: String): CategoryId? = NAMES.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }.takeIf { it >= 0 }?.let { CategoryId(it + 1L) }
}

/** One entry of the shipped merchant list (data in `merchants.json`, Requirement 31.5). */
data class DefaultMerchant(val name: String, val aliases: List<String>, val categoryName: String)
