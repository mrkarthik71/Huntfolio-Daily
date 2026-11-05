package app.assistant.data.db

import android.content.Context
import app.assistant.data.money.MoneySeeder
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.EncryptedStoreGate
import app.assistant.domain.port.StoreStatus
import app.assistant.domain.port.UnlockFailure
import app.assistant.security.keys.KeyError
import app.assistant.security.keys.KeyManager
import app.assistant.security.log.Component
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.ErrorFingerprint
import app.assistant.security.log.SafeLog
import app.assistant.security.log.UnlockOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** Gives data-layer code the open database. A test double returns an in-memory one. */
fun interface DbProvider {
    fun database(): AppDatabase
}

/** Thrown when code asks for the database while it is not open; screens check the status first. */
class StoreUnavailableException : IllegalStateException("Encrypted_Store is not open")

/**
 * Opens the Encrypted_Store once per process and keeps it open. If the key cannot be
 * obtained, the app shows the "data cannot be unlocked" screen and never creates a new key.
 */
@Singleton
class DatabaseHolder @Inject constructor(@param:ApplicationContext private val context: Context, private val keys: KeyManager, private val log: SafeLog) :
    EncryptedStoreGate,
    DbProvider {
    private val _status = MutableStateFlow<StoreStatus>(StoreStatus.NotOpened)
    override val status: StateFlow<StoreStatus> = _status.asStateFlow()

    @Volatile
    private var db: AppDatabase? = null

    @Synchronized
    override fun open(): StoreStatus {
        if (db != null) return StoreStatus.Open
        val result = when (val key = keys.databaseKey()) {
            is Outcome.Err -> StoreStatus.Unavailable(key.error.toFailure())
            is Outcome.Ok -> try {
                val opened = DatabaseFactory.open(context, key.value)
                // Touch the database so a wrong key or damaged file fails here, not on first query.
                opened.openHelper.writableDatabase
                db = opened
                seedMoney(opened)
                StoreStatus.Open
            } catch (e: Exception) {
                key.value.fill(0)
                log.record(DiagnosticEvent.DatabaseOpenFailed(ErrorFingerprint.of(e)))
                StoreStatus.Unavailable(UnlockFailure.DATABASE_ERROR)
            }
        }
        log.record(DiagnosticEvent.StartupUnlock(result.outcome()))
        _status.value = result
        return result
    }

    /** The open database. Throws [StoreUnavailableException] when it cannot be opened. */
    override fun database(): AppDatabase {
        db?.let { return it }
        open()
        return db ?: throw StoreUnavailableException()
    }

    /** The database if it is already open, without trying to open it. */
    fun databaseIfOpen(): AppDatabase? = db

    /**
     * Fills the shipped categories, Merchants and senders into a new Phase 3 store. A failure is
     * logged and ignored: the Phase 1 and 2 features must still open, and the next start tries again.
     */
    private fun seedMoney(opened: AppDatabase) {
        try {
            val seeder = MoneySeeder { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
            runBlocking { seeder.seed(opened) }
        } catch (e: Exception) {
            log.record(DiagnosticEvent.ErrorCaught(Component.DATABASE, ErrorFingerprint.of(e)))
        }
    }

    /** Used by the Data_Wiper before deleting files. */
    @Synchronized
    fun close() {
        db?.close()
        db = null
        _status.value = StoreStatus.NotOpened
    }

    private fun KeyError.toFailure(): UnlockFailure = when (this) {
        KeyError.KeystoreUnavailable -> UnlockFailure.KEYSTORE_UNAVAILABLE
        KeyError.KeyInvalidated -> UnlockFailure.KEY_INVALIDATED
        KeyError.BlobCorrupt -> UnlockFailure.KEY_FILE_DAMAGED
    }

    private fun StoreStatus.outcome(): UnlockOutcome = when (this) {
        StoreStatus.Open, StoreStatus.NotOpened -> UnlockOutcome.OPENED
        is StoreStatus.Unavailable -> when (reason) {
            UnlockFailure.KEYSTORE_UNAVAILABLE -> UnlockOutcome.KEYSTORE_UNAVAILABLE
            UnlockFailure.KEY_INVALIDATED -> UnlockOutcome.KEY_INVALIDATED
            UnlockFailure.KEY_FILE_DAMAGED -> UnlockOutcome.BLOB_CORRUPT
            UnlockFailure.DATABASE_ERROR -> UnlockOutcome.DATABASE_ERROR
        }
    }
}
