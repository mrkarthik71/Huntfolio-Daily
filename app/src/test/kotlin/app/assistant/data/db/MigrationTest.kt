package app.assistant.data.db

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.assistant.data.db.entity.DiagnosticEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration harness (Requirements 65.2, 65.6). Each phase that bumps the schema version adds
 * nothing here: the loop below migrates from every exported version to the current one using
 * ALL_MIGRATIONS and validates the result against the exported schema.
 *
 * Runs on framework SQLite under Robolectric; schema migrations do not depend on encryption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    /**
     * Room 2.8's helper compares the configured database name with the resolved file path, so
     * tests pass the absolute path as the name.
     */
    private fun dbPath(name: String): String = ApplicationProvider.getApplicationContext<Application>().getDatabasePath(name).absolutePath

    @Test
    fun currentSchemaIsExported() {
        helper.createDatabase(dbPath(DB), AppDatabase.VERSION).close()
    }

    @Test
    fun everyEarlierVersionMigratesToCurrent() {
        for (from in 1 until AppDatabase.VERSION) {
            val path = dbPath("$DB-from-$from")
            helper.createDatabase(path, from).close()
            helper.runMigrationsAndValidate(path, AppDatabase.VERSION, true, *ALL_MIGRATIONS)
        }
    }

    @Test
    fun migrationsFormAChainWithoutGaps() {
        val starts = ALL_MIGRATIONS.map { it.startVersion }.sorted()
        assertEquals((1 until AppDatabase.VERSION).toList(), starts)
        assertTrue(ALL_MIGRATIONS.all { it.endVersion == it.startVersion + 1 })
    }

    @Test
    fun diagnosticRingBufferKeepsOnlyTheNewestRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.diagnosticDao()
        repeat(12) { i -> dao.insertAndTrim(DiagnosticEventEntity(at = i.toLong(), code = "c$i", detail = ""), keep = 5) }
        assertEquals(5, dao.count())
        db.close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
