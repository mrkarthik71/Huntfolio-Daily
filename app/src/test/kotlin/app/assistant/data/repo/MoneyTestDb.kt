package app.assistant.data.repo

import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.money.MoneySeeder
import kotlinx.coroutines.runBlocking
import java.io.File

/** An in-memory Phase 3 database for storage-level tests: seeded once, with the Transaction tables emptied between iterations. */
internal object MoneyTestDb {
    fun create(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { MoneySeeder { File("src/main/assets/$it").readText() }.seed(db) }
        return db
    }

    private val RESET_TABLES = listOf(
        "txn",
        "parsed_fact",
        "tombstone",
        "forced_merge",
        "merchant",
        "merchant_alias",
        "category_correction",
        "processed_source_ref",
        "review_item",
        "bill_proposal",
        "bill",
        "bill_paid_proposal",
    )

    /** Empties everything a message run writes. The built-in categories, senders and Money_App_List stay. */
    fun reset(db: AppDatabase) {
        val sql = db.openHelper.writableDatabase
        RESET_TABLES.forEach { sql.execSQL("DELETE FROM $it") }
    }

    private fun tableNames(db: AppDatabase): List<String> = db.openHelper.writableDatabase
        .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'")
        .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    /**
     * Every text value in every table, to show where a string is, or is not, stored. [skip] names (table, column) pairs to leave
     * out, such as the Review_Queue's redacted text, which is allowed to hold message text.
     */
    fun allText(db: AppDatabase, skip: Set<Pair<String, String>> = emptySet()): List<String> {
        val sql = db.openHelper.writableDatabase
        return tableNames(db).flatMap { table ->
            sql.query("SELECT * FROM `$table`").use { c ->
                val columns = (0 until c.columnCount).filter { (table to c.getColumnName(it)) !in skip }
                generateSequence { if (c.moveToNext()) c else null }.flatMap { row -> columns.mapNotNull { row.stringOrNull(it) } }.toList()
            }
        }
    }

    /** The number of rows in each table, so a test can show that a message changed nothing. */
    fun rowCounts(db: AppDatabase): Map<String, Int> {
        val sql = db.openHelper.writableDatabase
        return tableNames(db).associateWith { t ->
            sql.query("SELECT COUNT(*) FROM `$t`").use {
                it.moveToFirst()
                it.getInt(0)
            }
        }
    }

    private fun Cursor.stringOrNull(i: Int): String? = if (getType(i) == Cursor.FIELD_TYPE_STRING) getString(i) else null
}
