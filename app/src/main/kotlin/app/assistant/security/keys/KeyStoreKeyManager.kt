package app.assistant.security.keys

import app.assistant.domain.error.Outcome
import java.security.SecureRandom

/**
 * Key_Manager logic (design: "Database key" and "Source_Ref HMAC key").
 *
 * - On first launch (no database yet) it creates a Keystore wrapping key and two random
 *   32-byte keys, wraps them and stores the blobs in no-backup files.
 * - If a database already exists but a key cannot be unwrapped, it returns an error and never
 *   creates a new key, so existing data is left untouched (Requirement 1.5).
 * - The database and Source_Ref keys are not bound to user authentication because background
 *   ingest needs them while the app is locked (ADR 0003).
 */
class KeyStoreKeyManager(
    private val wrapper: KeyWrapper,
    private val store: WrappedKeyStore,
    private val databaseExists: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
) : KeyManager {

    private val lock = Any()
    private var cachedDbKey: ByteArray? = null
    private var cachedRefKey: ByteArray? = null

    /** Callers get a copy, so clearing it does not clear the cached key. */
    override fun databaseKey(): Outcome<ByteArray, KeyError> = synchronized(lock) {
        when (val loaded = ensureLoaded()) {
            is Outcome.Ok -> Outcome.Ok(checkNotNull(cachedDbKey).copyOf())
            is Outcome.Err -> loaded
        }
    }

    override fun sourceRefKey(): Outcome<ByteArray, KeyError> = synchronized(lock) {
        when (val loaded = ensureLoaded()) {
            is Outcome.Ok -> Outcome.Ok(checkNotNull(cachedRefKey).copyOf())
            is Outcome.Err -> loaded
        }
    }

    /** Called under [lock]. */
    private fun ensureLoaded(): Outcome<Unit, KeyError> {
        if (cachedDbKey != null && cachedRefKey != null) return Outcome.Ok(Unit)
        return when (val result = loadOrCreate()) {
            is Outcome.Ok -> {
                cachedDbKey = result.value.first
                cachedRefKey = result.value.second
                Outcome.Ok(Unit)
            }
            is Outcome.Err -> result
        }
    }

    override fun encryptSecret(plain: ByteArray): Outcome<ByteArray, KeyError> = guarded {
        if (!wrapper.hasKey(SECRET_ALIAS)) wrapper.createKey(SECRET_ALIAS)
        wrapper.wrap(SECRET_ALIAS, plain)
    }

    override fun decryptSecret(blob: ByteArray): Outcome<ByteArray, KeyError> = guarded {
        if (!wrapper.hasKey(SECRET_ALIAS)) throw KeyWrapperException.Invalidated(null)
        wrapper.unwrap(SECRET_ALIAS, blob)
    }

    override fun deleteAll() = synchronized(lock) {
        cachedDbKey?.fill(0)
        cachedRefKey?.fill(0)
        cachedDbKey = null
        cachedRefKey = null
        runCatching { store.delete(DB_KEY_FILE) }
        runCatching { store.delete(REF_KEY_FILE) }
        runCatching { wrapper.deleteKey(WRAP_ALIAS) }
        runCatching { wrapper.deleteKey(SECRET_ALIAS) }
        Unit
    }

    /** Returns (database key, Source_Ref key). Called under [lock]. */
    private fun loadOrCreate(): Outcome<Pair<ByteArray, ByteArray>, KeyError> = guarded {
        val dbBlob = store.read(DB_KEY_FILE)
        val refBlob = store.read(REF_KEY_FILE)
        when {
            // First launch, or a first launch that stopped before setup finished: no data to protect yet.
            dbBlob == null && !databaseExists() -> create()
            // Data exists but its key file is gone: never replace the key (Requirement 1.5).
            dbBlob == null -> throw KeyWrapperException.Corrupt(null)
            !wrapper.hasKey(WRAP_ALIAS) -> throw KeyWrapperException.Invalidated(null)
            else -> {
                val dbKey = wrapper.unwrap(WRAP_ALIAS, dbBlob)
                // The Source_Ref key is created together with the database key. If only its file is
                // missing (for example an older install), create it: it protects no stored data by itself.
                val refKey = if (refBlob != null) {
                    wrapper.unwrap(WRAP_ALIAS, refBlob)
                } else {
                    newKey().also { store.write(REF_KEY_FILE, wrapper.wrap(WRAP_ALIAS, it)) }
                }
                if (dbKey.size != KEY_BYTES || refKey.size != KEY_BYTES) throw KeyWrapperException.Corrupt(null)
                dbKey to refKey
            }
        }
    }

    private fun create(): Pair<ByteArray, ByteArray> {
        wrapper.createKey(WRAP_ALIAS)
        val dbKey = newKey()
        val refKey = newKey()
        store.write(REF_KEY_FILE, wrapper.wrap(WRAP_ALIAS, refKey))
        // Written last: the database key file is the marker that setup finished.
        store.write(DB_KEY_FILE, wrapper.wrap(WRAP_ALIAS, dbKey))
        return dbKey to refKey
    }

    private fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    private inline fun <T> guarded(block: () -> T): Outcome<T, KeyError> = try {
        Outcome.Ok(block())
    } catch (e: KeyWrapperException.Invalidated) {
        Outcome.Err(KeyError.KeyInvalidated)
    } catch (e: KeyWrapperException.Corrupt) {
        Outcome.Err(KeyError.BlobCorrupt)
    } catch (e: KeyWrapperException.Unavailable) {
        Outcome.Err(KeyError.KeystoreUnavailable)
    }

    companion object {
        const val KEY_BYTES = 32
        const val WRAP_ALIAS = "assistant.wrap.v1"
        const val SECRET_ALIAS = "assistant.secret.v1"
        const val DB_KEY_FILE = "db.key.enc"
        const val REF_KEY_FILE = "ref.key.enc"
    }
}
