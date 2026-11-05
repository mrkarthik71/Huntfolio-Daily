package app.assistant.security.keys

import app.assistant.domain.error.Outcome

/** Why a key could not be obtained. The app never replaces a lost key silently (Requirement 1.5). */
sealed interface KeyError {
    /** The Android Keystore could not be used (provider error, device problem). */
    data object KeystoreUnavailable : KeyError

    /** The Keystore wrapping key is gone or permanently invalidated. */
    data object KeyInvalidated : KeyError

    /** The wrapped key file is missing (while data exists), damaged or fails authentication. */
    data object BlobCorrupt : KeyError
}

interface KeyManager {
    /** Creates the database key on first launch (Requirement 1.2); Err if it cannot be unwrapped (Requirement 1.5). */
    fun databaseKey(): Outcome<ByteArray, KeyError>

    /** Key for Source_Ref HMACs (ADR 0006). Created together with the database key. */
    fun sourceRefKey(): Outcome<ByteArray, KeyError>

    /** Encrypts a secret (mail credential, Cloud AI key) with a separate Keystore key. */
    fun encryptSecret(plain: ByteArray): Outcome<ByteArray, KeyError>

    fun decryptSecret(blob: ByteArray): Outcome<ByteArray, KeyError>

    /** Deletes every key and wrapped key file. Used only by the Data_Wiper. */
    fun deleteAll()
}
