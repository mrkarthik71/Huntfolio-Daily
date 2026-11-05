package app.assistant.data.money

import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Merchant
import app.assistant.domain.model.MerchantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Task 19.8: the shipped default Merchant list (Requirement 31.5). */
class DefaultMerchantsTest {
    private val merchants = DefaultMerchantsJson.parse(File("src/main/assets/merchants.json").readText()).getOrThrow()

    @Test
    fun `the list is non-trivial and every entry has an alias and a built-in category`() {
        assertTrue(merchants.size >= 15, "expected a useful default list, got ${merchants.size}")
        merchants.forEach {
            assertTrue(it.aliases.isNotEmpty(), "${it.name}: needs at least one alias or VPA")
            assertNotNull(BuiltinCategories.idOf(it.categoryName), "${it.name}: '${it.categoryName}' is not a built-in category")
        }
    }

    @Test
    fun `Swiggy maps to Food`() {
        val swiggy = merchants.first { it.name == "Swiggy" }
        assertEquals("Food", swiggy.categoryName)
    }

    @Test
    fun `no name or alias is claimed by two different merchants, so matching is unambiguous`() {
        val claims = merchants.flatMap { m -> (listOf(m.name) + m.aliases).map { Categoriser.normalise(it) }.toSet().map { it to m.name } }
        val duplicated = claims.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        assertTrue(duplicated.isEmpty(), "claimed by more than one merchant: $duplicated")
    }

    @Test
    fun `the list drives the Categoriser end to end`() {
        val asMerchants = merchants.mapIndexed { i, m ->
            Merchant(MerchantId(i + 1L), m.name, m.aliases, BuiltinCategories.idOf(m.categoryName))
        }
        val hit = Categoriser.matchMerchant("  swiggy instamart ", asMerchants)
        assertEquals("Swiggy", hit?.name)
        assertEquals(BuiltinCategories.idOf("Food"), Categoriser.categoryFor(hit, null))
        assertEquals(CategoryId.UNCATEGORISED, Categoriser.categoryFor(Categoriser.matchMerchant("Corner Shop 12", asMerchants), null))
    }

    @Test
    fun `built-in category ids are their position plus one, with Uncategorised first`() {
        assertEquals(CategoryId.UNCATEGORISED, BuiltinCategories.idOf(BuiltinCategories.UNCATEGORISED_NAME))
        assertEquals(BuiltinCategories.NAMES.size, BuiltinCategories.NAMES.map { it.lowercase() }.toSet().size, "category names are unique")
        assertEquals(CategoryId(2), BuiltinCategories.idOf("food"))
    }

    @Test
    fun `a malformed list is rejected instead of silently shipping an entry with no category`() {
        assertTrue(DefaultMerchantsJson.parse("""{"merchants":[{"name":"X","aliases":["x"]}]}""").isFailure)
        assertTrue(DefaultMerchantsJson.parse("""{"merchants":[{"aliases":["x"],"category":"Food"}]}""").isFailure)
        assertTrue(DefaultMerchantsJson.parse("not json").isFailure)
        assertEquals(emptyList<Any>(), DefaultMerchantsJson.parse("""{"merchants":[]}""").getOrThrow())
    }
}
