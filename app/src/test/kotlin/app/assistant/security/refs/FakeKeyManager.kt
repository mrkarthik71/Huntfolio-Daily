package app.assistant.security.refs

import app.assistant.domain.error.Outcome
import app.assistant.security.keys.KeyError
import app.assistant.security.keys.KeyManager

/**
 * A fixed, non-secret key for tests only. Like the real manager it hands out a fresh copy on every
 * call, and it remembers that copy ([lastIssued]) so a test can check the caller wiped it.
 */
class FakeKeyManager(private val refKey: ByteArray = ByteArray(32) { it.toByte() }) : KeyManager {
    var failure: KeyError? = null
    var lastIssued: ByteArray? = null

    override fun databaseKey(): Outcome<ByteArray, KeyError> = Outcome.Ok(refKey.copyOf())

    override fun sourceRefKey(): Outcome<ByteArray, KeyError> {
        failure?.let { return Outcome.Err(it) }
        return Outcome.Ok(refKey.copyOf().also { lastIssued = it })
    }

    override fun encryptSecret(plain: ByteArray): Outcome<ByteArray, KeyError> = Outcome.Ok(plain)

    override fun decryptSecret(blob: ByteArray): Outcome<ByteArray, KeyError> = Outcome.Ok(blob)

    override fun deleteAll() = Unit
}
