package app.assistant.data.money

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.model.BuiltinCategories
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Seeding of the shipped categories, Merchants and Bank_Sender_List (task 20.1). Runs on framework SQLite under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MoneySeederTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun shipped(name: String) = File("src/main/assets/$name").readText()

    private val shippedSeeder = MoneySeeder { shipped(it) }

    private fun <T> query(sql: String, read: (android.database.Cursor) -> T): List<T> =
        db.openHelper.writableDatabase.query(sql).use { c -> generateSequence { if (c.moveToNext()) read(c) else null }.toList() }

    private fun count(table: String): Int = query("SELECT COUNT(*) FROM $table") { it.getInt(0) }.single()

    @Test
    fun builtInCategoriesGetTheirPositionPlusOneAsId() {
        assertTrue(runBlocking { shippedSeeder.seed(db) })
        val rows = query("SELECT id, name, builtin FROM category ORDER BY id") { Triple(it.getLong(0), it.getString(1), it.getInt(2)) }
        assertEquals(BuiltinCategories.NAMES.mapIndexed { i, n -> Triple(i + 1L, n, 1) }, rows)
        assertEquals("Uncategorised", rows.first { it.first == 1L }.second)
    }

    @Test
    fun everyShippedMerchantIsStoredWithItsCategoryAndNormalisedAliases() {
        runBlocking { shippedSeeder.seed(db) }
        val expected = DefaultMerchantsJson.parse(shipped("merchants.json")).getOrThrow()
        val stored = query("SELECT id, name, name_norm, default_category_id, builtin FROM merchant ORDER BY id") {
            listOf(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getInt(4))
        }
        assertEquals(expected.map { it.name }, stored.map { it[1] })
        assertEquals(expected.map { Categoriser.normalise(it.name) }, stored.map { it[2] })
        assertEquals(expected.map { checkNotNull(BuiltinCategories.idOf(it.categoryName)).value }, stored.map { it[3] })
        assertTrue(stored.all { it[4] == 1 })

        val aliasesByMerchant = query("SELECT merchant_id, alias_norm FROM merchant_alias") { it.getLong(0) to it.getString(1) }
            .groupBy({ it.first }, { it.second })
        expected.forEachIndexed { index, m ->
            val id = stored[index][0] as Long
            assertEquals(m.name, m.aliases.map(Categoriser::normalise).distinct().sorted(), aliasesByMerchant[id].orEmpty().sorted())
        }
    }

    @Test
    fun everyShippedSenderIsStoredAsBuiltInAndEnabled() {
        runBlocking { shippedSeeder.seed(db) }
        val expected = BankSenderJson.parse(shipped("bank_senders.json")).getOrThrow()
        val stored = query("SELECT core, prefix_pattern, suffix_pattern, builtin, enabled FROM sender_entry ORDER BY id") {
            listOf(it.getString(0), it.getString(1), it.getString(2), it.getInt(3), it.getInt(4))
        }
        assertEquals(expected.map { listOf(it.coreHeader, it.prefixPattern, it.suffixPattern, 1, 1) }, stored)
        assertTrue(expected.isNotEmpty())
    }

    @Test
    fun seedingTwiceChangesNothing() {
        assertTrue(runBlocking { shippedSeeder.seed(db) })
        val before = listOf("category", "merchant", "merchant_alias", "sender_entry").map { count(it) }
        assertFalse(runBlocking { shippedSeeder.seed(db) })
        assertEquals(before, listOf("category", "merchant", "merchant_alias", "sender_entry").map { count(it) })
    }

    @Test
    fun aMerchantTheUserDeletedDoesNotComeBack() {
        runBlocking { shippedSeeder.seed(db) }
        val before = count("merchant")
        db.openHelper.writableDatabase.execSQL("DELETE FROM merchant WHERE name_norm = 'swiggy'")
        runBlocking { shippedSeeder.seed(db) }
        assertEquals(before - 1, count("merchant"))
    }

    @Test
    fun userCategoriesDoNotCountAsSeeded() {
        db.openHelper.writableDatabase.execSQL("INSERT INTO category (id, name, builtin) VALUES (50, 'Pets', 0)")
        assertTrue(runBlocking { shippedSeeder.seed(db) })
        assertEquals(BuiltinCategories.NAMES.size + 1, count("category"))
    }

    @Test
    fun aMerchantWithAnUnknownCategoryFailsAndWritesNothing() {
        val seeder = MoneySeeder { name ->
            if (name == MoneySeeder.MERCHANTS_ASSET) """{"merchants":[{"name":"X","aliases":[],"category":"Nonsense"}]}""" else shipped(name)
        }
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { seeder.seed(db) } }
        assertTrue(error.message.orEmpty().contains("Nonsense"))
        assertEquals(0, count("category"))
    }

    @Test
    fun anAliasSharedByTwoMerchantsKeepsTheFirstOwnerAndDoesNotFail() {
        val json = """{"merchants":[
            {"name":"One","aliases":["Shared"],"category":"Food"},
            {"name":"Two","aliases":["shared "],"category":"Shopping"}]}"""
        assertTrue(runBlocking { MoneySeeder { if (it == MoneySeeder.MERCHANTS_ASSET) json else shipped(it) }.seed(db) })
        assertEquals(2, count("merchant"))
        val owners = query("SELECT m.name FROM merchant_alias a JOIN merchant m ON m.id = a.merchant_id WHERE a.alias_norm = 'shared'") { it.getString(0) }
        assertEquals(listOf("One"), owners)
    }
}
