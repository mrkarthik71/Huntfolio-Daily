// Feature: Phase 3 Money — Property 45: Categoriser precedence and matching.
package app.assistant.domain.engine.categorise

import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Merchant
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.TransactionId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val CHARS = "abcAB "

/** Short names over a tiny alphabet, with stray spaces and mixed case, so matches and near-misses both occur. */
private val arbText: Arb<String> = arbitrary {
    (1..Arb.int(0..4).bind()).map { CHARS[Arb.int(CHARS.indices).bind()] }.joinToString("")
}

private val arbMerchant: Arb<Merchant> = arbitrary {
    val aliases = (1..Arb.int(0..3).bind()).map { arbText.bind() }
    Merchant(
        id = MerchantId(Arb.int(1..1_000_000).bind().toLong()),
        name = arbText.bind(),
        aliases = aliases,
        defaultCategory = if (Arb.boolean().bind()) CategoryId(Arb.int(2..11).bind().toLong()) else null,
    )
}

class CategoriserTest {

    /** The matching rule of Requirement 31.2 written another way: trimmed strings compared ignoring case. */
    private fun sameText(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    @Test
    fun `Property 45 - a Transaction matches a Merchant exactly when its trimmed, case-folded text equals the name or an alias`() = runTest {
        checkAll(Arb.list(arbMerchant, 0..6), arbText) { merchants, query ->
            val expected = merchants.firstOrNull { m -> query.isNotBlank() && (sameText(query, m.name) || m.aliases.any { sameText(query, it) }) }
            assertEquals(expected, Categoriser.matchMerchant(query, merchants), "query '$query' over $merchants")
        }
    }

    @Test
    fun `a blank or whitespace-only text matches no Merchant, even one with a blank alias`() {
        val blankAlias = Merchant(MerchantId(1), "X", listOf("", "  "), null)
        assertNull(Categoriser.matchMerchant("", listOf(blankAlias)))
        assertNull(Categoriser.matchMerchant("   ", listOf(blankAlias)))
    }

    @Test
    fun `matching is on the whole text and never on part of it`() {
        val swiggy = Merchant(MerchantId(1), "Swiggy", listOf("Swiggy Instamart"), CategoryId(2))
        assertNull(Categoriser.matchMerchant("Swiggy Instamart Pune", listOf(swiggy)))
        assertNull(Categoriser.matchMerchant("Swigg", listOf(swiggy)))
        assertEquals(swiggy, Categoriser.matchMerchant("  sWiGgY INSTAMART ", listOf(swiggy)))
    }

    @Test
    fun `Property 45 - the category is the latest correction, else the default, else Uncategorised`() = runTest {
        val arbCategory = arbitrary { if (Arb.boolean().bind()) CategoryId(Arb.int(2..11).bind().toLong()) else null }
        checkAll(arbCategory, arbCategory, Arb.boolean()) { correction, default, hasMerchant ->
            val merchant = if (hasMerchant) Merchant(MerchantId(1), "M", emptyList(), default) else null
            val expected = correction ?: (if (hasMerchant) default else null) ?: CategoryId.UNCATEGORISED
            assertEquals(expected, Categoriser.categoryFor(merchant, correction))
        }
    }

    @Test
    fun `the three precedence cases by example`() {
        val food = CategoryId(2)
        val shopping = CategoryId(4)
        val swiggy = Merchant(MerchantId(1), "Swiggy", listOf("SWIGGY"), food)
        assertEquals(shopping, Categoriser.categoryFor(swiggy, shopping), "a correction beats the default")
        assertEquals(food, Categoriser.categoryFor(swiggy, null), "the default applies without a correction")
        assertEquals(CategoryId.UNCATEGORISED, Categoriser.categoryFor(Merchant(MerchantId(2), "Corner Shop", emptyList(), null), null))
        assertEquals(CategoryId.UNCATEGORISED, Categoriser.categoryFor(null, null), "no Merchant at all")
        assertEquals(CategoryId(9), Categoriser.categoryFor(null, null, uncategorised = CategoryId(9)))
    }

    @Test
    fun `Property 45 - an unmatched non-empty name makes exactly one new Merchant that then matches itself`() = runTest {
        checkAll(Arb.list(arbMerchant, 0..5), arbText) { merchants, query ->
            if (Categoriser.matchMerchant(query, merchants) == null) {
                val created = Categoriser.newMerchant(query)
                if (query.isBlank()) {
                    assertNull(created)
                } else {
                    val merchant = checkNotNull(created)
                    assertEquals(query.trim(), merchant.name)
                    assertEquals(listOf(query.trim()), merchant.aliases)
                    assertNull(merchant.defaultCategory, "a new Merchant has no default category")
                    assertNull(merchant.id)
                    assertEquals(
                        merchant,
                        Categoriser.matchMerchant(query.uppercase(), merchants + merchant),
                        "the new Merchant matches the same payee next time",
                    )
                    assertEquals(CategoryId.UNCATEGORISED, Categoriser.categoryFor(merchant, null))
                }
            }
        }
    }

    @Test
    fun `Property 45 - applying a correction changes every Transaction of the Merchant except those the User set individually`() = runTest {
        val arbTxn = arbitrary {
            CategorisedTransaction(
                id = TransactionId(Arb.int(1..1_000_000).bind().toLong()),
                merchantId = if (Arb.boolean().bind()) MerchantId(Arb.element(1L, 2L).bind()) else null,
                category = CategoryId(Arb.int(1..11).bind().toLong()),
                categoryUserSet = Arb.boolean().bind(),
            )
        }
        checkAll(Arb.list(arbTxn, 0..12), Arb.element(1L, 2L), Arb.int(2..11)) { earlier, merchant, category ->
            val newCategory = CategoryId(category.toLong())
            val result = Categoriser.applyCorrectionToEarlier(earlier, MerchantId(merchant), newCategory)
            assertEquals(earlier.size, result.size)
            earlier.zip(result).forEach { (before, after) ->
                val shouldChange = before.merchantId == MerchantId(merchant) && !before.categoryUserSet
                assertEquals(if (shouldChange) newCategory else before.category, after.category)
                assertEquals(before.copy(category = after.category), after, "nothing but the category may change")
            }
        }
    }
}
