package app.assistant.security.keys

/**
 * Encrypts and decrypts small blobs with a non-exportable key identified by an alias.
 * The Android implementation uses the Android Keystore; tests use an in-memory fake.
 */
interface KeyWrapper {
    fun hasKey(alias: String): Boolean

    /** Creates the wrapping key for [alias] if it does not exist. */
    fun createKey(alias: String)

    fun wrap(alias: String, plain: ByteArray): ByteArray

    fun unwrap(alias: String, blob: ByteArray): ByteArray

    fun deleteKey(alias: String)
}

/** Thrown by [KeyWrapper] implementations; [KeyStoreKeyManager] maps them to [KeyError]. */
sealed class KeyWrapperException(cause: Throwable?) : Exception(null, cause) {
    class Unavailable(cause: Throwable?) : KeyWrapperException(cause)

    class Invalidated(cause: Throwable?) : KeyWrapperException(cause)

    class Corrupt(cause: Throwable?) : KeyWrapperException(cause)
}

/** Stores wrapped key blobs, by name, in files that are never backed up. */
interface WrappedKeyStore {
    fun read(name: String): ByteArray?

    fun write(name: String, blob: ByteArray)

    fun delete(name: String)
}
