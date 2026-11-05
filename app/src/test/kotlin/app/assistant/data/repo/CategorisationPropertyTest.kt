// Feature: Phase 3 Money — Property 45: Categoriser precedence and matching.
package app.assistant.data.repo

import android.app.Application
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.CategoryCorrectionEntity
import app.assistant.data.db.entity.MerchantAliasEntity
import app.assistant.data.db.entity.MerchantEntity
import app.assistant.domain.engine.categorise.CategorisedTransaction
import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.engine.dedupe.fact
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Merchant
import app.assistant.domain.model.MerchantId
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.testing.FakeTimeSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/** A Merchant to put in the store: [corrections] are in the order the User made them, the last being the latest. */
private data class MerchantSpec(val name: String, val aliases: List<String>, val defaultCategory: Int?, val corrections: List<Int>)

private data class CategoryScenario(
    val merchants: List<MerchantSpec>,
    /** The merchant name or VPA of each message; null and blank mean the message names none. */
    val texts: List<String?>,
    /** Transactions the User then sets a category on individually: (position, category). */
    val marks: List<Pair<Int, Int>>,
    val anchor: Int,
    val newCategory: Int,
)

/**
 * Property 45 on the real repository (Requirements 31.1, 31.2, 31.3, 31.6, 31.7), with the pure [Categoriser] as the
 * oracle: a message matches a Merchant exactly when its trimmed, case-folded text equals the Merchant's name or an alias;
 * its category is the latest correction, else the default, else Uncategorised; an unmatched non-empty text creates one new
 * Merchant; and applying a correction to earlier Transactions changes all of them but those set individually.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CategorisationPropertyTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomTransactionRepository

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        repo = RoomTransactionRepository(DbProvider { db }, FakeTimeSource(), DuplicateWindowProvider { Duration.ofMinutes(15) })
    }

    @After
    fun tearDown() = db.close()

    private val categoryCount = BuiltinCategories.NAMES.size

    private val scenarios = arbitrary {
        val merchants = ArrayList<MerchantSpec>()
        repeat(Arb.int(1..4).bind()) { i ->
            val aliases = ArrayList<String>()
            repeat(Arb.int(0..2).bind()) { j -> aliases += "alias$i-$j" }
            val corrections = ArrayList<Int>()
            repeat(Arb.int(0..3).bind()) { corrections += Arb.int(1..categoryCount).bind() }
            merchants += MerchantSpec(
                name = "Shop$i ${Arb.element(listOf("Cafe", "Mart", "Store")).bind()}",
                aliases = aliases,
                defaultCategory = if (Arb.boolean().bind()) Arb.int(1..categoryCount).bind() else null,
                corrections = corrections,
            )
        }
        val texts = ArrayList<String?>()
        repeat(Arb.int(1..6).bind()) {
            val owner = merchants[Arb.int(0 until merchants.size).bind()]
            val raw = if (owner.aliases.isNotEmpty() && Arb.boolean().bind()) owner.aliases[Arb.int(0 until owner.aliases.size).bind()] else owner.name
            texts += when (Arb.int(0..5).bind()) {
                0 -> raw
                1 -> raw.uppercase()
                2 -> "  ${raw.lowercase()} "
                3 -> "Unknown ${Arb.int(0..2).bind()}"
                // Near misses: a longer name and a name with a letter missing are not matches.
                4 -> if (Arb.boolean().bind()) "${owner.name}x" else owner.name.dropLast(1)
                else -> Arb.element(listOf(null, "", "   ")).bind()
            }
        }
        val marks = ArrayList<Pair<Int, Int>>()
        repeat(Arb.int(0..3).bind()) { marks += Arb.int(0..9).bind() to Arb.int(1..categoryCount).bind() }
        CategoryScenario(merchants, texts, marks, Arb.int(0..9).bind(), Arb.int(1..categoryCount).bind())
    }

    private suspend fun all(): List<Transaction> = repo.transactions().first().sortedWith(compareBy({ it.at }, { it.id.value }))

    private fun merchantCount(): Int = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM merchant").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `Property 45 - matching, precedence, new Merchants and applying a correction follow the Categoriser`() {
        runBlocking {
            checkAll(200, scenarios) { s ->
                MoneyTestDb.reset(db)
                val catalog = db.merchantCatalogDao()

                // The Merchants, as the oracle sees them and as they are stored.
                val oracle = ArrayList<Merchant>()
                val latestCorrection = HashMap<MerchantId, CategoryId>()
                for (m in s.merchants) {
                    val id = catalog.insertMerchant(
                        MerchantEntity(
                            name = m.name,
                            nameNorm = Categoriser.normalise(m.name),
                            defaultCategoryId = m.defaultCategory?.toLong(),
                            builtin = false,
                        ),
                    )
                    m.aliases.forEach { catalog.insertAlias(MerchantAliasEntity(merchantId = id, aliasNorm = Categoriser.normalise(it))) }
                    m.corrections.forEachIndexed { k, c ->
                        catalog.insertCorrection(
                            CategoryCorrectionEntity(
                                merchantId = id,
                                categoryId = c.toLong(),
                                at =
                                (k + 1) * 1_000L,
                            ),
                        )
                    }
                    oracle += Merchant(MerchantId(id), m.name, m.aliases, m.defaultCategory?.let { CategoryId(it.toLong()) })
                    m.corrections.lastOrNull()?.let { latestCorrection[MerchantId(id)] = CategoryId(it.toLong()) }
                }

                // Matching, precedence and creation (Requirements 31.1, 31.2, 31.7).
                s.texts.forEachIndexed { index, text ->
                    val stored = (repo.ingest(fact(index + 1, merchant = text, atSecond = index * 86_400L, origin = "BANKA")) as Outcome.Ok).value
                    val txn = checkNotNull(repo.get(stored.transaction))
                    val matched = Categoriser.matchMerchant(text.orEmpty(), oracle)
                    when {
                        matched != null -> {
                            assertEquals(matched.id, txn.merchantId)
                            assertEquals(Categoriser.categoryFor(matched, latestCorrection[matched.id]), txn.category)
                        }
                        !text.isNullOrBlank() -> {
                            val newId = checkNotNull(txn.merchantId)
                            assertTrue(oracle.none { it.id == MerchantId(newId.value) })
                            assertEquals(text.trim(), txn.merchantName)
                            assertEquals(CategoryId.UNCATEGORISED, txn.category)
                            oracle += checkNotNull(Categoriser.newMerchant(text)).copy(id = newId)
                        }
                        else -> {
                            assertNull(txn.merchantId)
                            assertEquals(CategoryId.UNCATEGORISED, txn.category)
                        }
                    }
                    // One new Merchant per unmatched text, never a duplicate.
                    assertEquals(oracle.size, merchantCount())
                }

                applyCorrection(s)
            }
        }
    }

    /** Requirement 31.6: the User sets some categories individually, then corrects one and applies it to earlier Transactions. */
    private suspend fun applyCorrection(s: CategoryScenario) {
        val withMerchant = all().filter { it.merchantId != null }
        if (withMerchant.isEmpty()) return
        s.marks.forEach { (position, category) -> repo.changeCategory(withMerchant[position % withMerchant.size].id, CategoryId(category.toLong())) }

        val anchor = withMerchant[s.anchor % withMerchant.size]
        val newCategory = CategoryId(s.newCategory.toLong())
        val offer = (repo.changeCategory(anchor.id, newCategory) as Outcome.Ok).value

        val before = all()
        val anchorNow = before.single { it.id == anchor.id }
        assertEquals(newCategory, anchorNow.category)
        val earlier = before.filter { it.at < anchorNow.at || (it.at == anchorNow.at && it.id.value < anchorNow.id.value) }
        val expected = Categoriser.applyCorrectionToEarlier(
            earlier.map { CategorisedTransaction(it.id, it.merchantId, it.category, it.categoryUserSet) },
            checkNotNull(anchorNow.merchantId),
            newCategory,
        )
        val changing = expected.zip(earlier).count { (e, o) -> e.category != o.category }

        if (changing == 0) {
            assertNull(offer)
        } else {
            assertNotNull(offer)
            assertEquals(changing, checkNotNull(offer).count)
            assertEquals(changing, repo.applyCategoryToEarlier(offer))
        }

        val after = all().associateBy { it.id }
        earlier.forEachIndexed { i, o -> assertEquals(expected[i].category, after.getValue(o.id).category) }
        val earlierIds = earlier.map { it.id }.toSet()
        // Everything that is not earlier than the changed Transaction stays exactly as it was.
        before.filter { it.id !in earlierIds }.forEach { assertEquals(it, after.getValue(it.id)) }
        // The User's own choices survive.
        before.filter { it.categoryUserSet }.forEach { assertEquals(it.category, after.getValue(it.id).category) }
    }
}
