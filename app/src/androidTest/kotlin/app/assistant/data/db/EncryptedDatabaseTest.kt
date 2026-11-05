package app.assistant.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.assistant.data.db.entity.AuditEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * Requirements 1.3, 1.4, 64.2(a): the Encrypted_Store cannot be read without its key and holds
 * no readable text. Runs on an emulator or device (SQLCipher is native code).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "encryption-test.db"
    private val marker = "MARKER-TASK-TITLE-7f3a9c"

    private fun newKey() = ByteArray(32).also(SecureRandom()::nextBytes)

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun writeMarker(key: ByteArray) = runBlocking {
        val db = DatabaseFactory.open(context, key.copyOf(), name)
        db.auditDao().insert(
            AuditEntryEntity(
                at = 1L, kind = "AI_SEND", source = null, itemCount = null, success = null,
                aiMode = null, promptType = null, payload = marker, parentId = null,
            ),
        )
        assertEquals(1, db.auditDao().count())
        db.close()
    }

    @Test
    fun withTheRightKeyTheDataReadsBack() = runBlocking {
        val key = newKey()
        writeMarker(key)
        val db = DatabaseFactory.open(context, key.copyOf(), name)
        assertEquals(marker, db.auditDao().page(10, 0).single().payload)
        db.close()
    }

    @Test
    fun withoutTheKeyTheFileIsNotADatabase() {
        writeMarker(newKey())
        val path = context.getDatabasePath(name).path
        try {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { plain ->
                plain.rawQuery("SELECT COUNT(*) FROM audit_entry", null).use { it.moveToFirst() }
            }
            fail("Plain SQLite opened the encrypted database")
        } catch (expected: Exception) {
            // android.database.sqlite.SQLiteDatabaseCorruptException or SQLiteNotADatabaseException
        }
    }

    @Test
    fun aWrongKeyReturnsNoRecords() = runBlocking {
        writeMarker(newKey())
        val db = DatabaseFactory.open(context, newKey(), name)
        try {
            db.auditDao().count()
            fail("A wrong key opened the database")
        } catch (expected: Exception) {
            // net.zetetic.database.sqlcipher.SQLiteNotADatabaseException or similar
        } finally {
            runCatching { db.close() }
        }
    }

    @Test
    fun rawBytesContainNoStoredText() {
        writeMarker(newKey())
        val dbFile = context.getDatabasePath(name)
        val files = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-journal")).filter { it.exists() }
        val needle = marker.toByteArray(Charsets.UTF_8)
        for (f in files) {
            assertFalse("Readable text found in ${f.name}", f.readBytes().containsSequence(needle))
        }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
