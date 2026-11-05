package app.assistant.security.keys

import app.assistant.domain.error.Outcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreKeyManagerTest {

    private val wrapper = FakeKeyWrapper()
    private val store = InMemoryKeyStore()
    private var dbExists = false
    private fun manager() = KeyStoreKeyManager(wrapper, store, databaseExists = { dbExists })

    @Test
    fun `first launch creates both keys and stores only wrapped blobs`() {
        val db = (manager().databaseKey() as Outcome.Ok).value
        val ref = (manager().sourceRefKey() as Outcome.Ok).value
        assertEquals(32, db.size)
        assertEquals(32, ref.size)
        assertFalse(db.contentEquals(ref))
        // Stored blobs are not the raw keys (Requirement 1.2).
        val stored = store.read(KeyStoreKeyManager.DB_KEY_FILE)!!
        assertFalse(stored.toList().windowed(32).any { it.toByteArray().contentEquals(db) })
    }

    @Test
    fun `later launches return the same keys`() {
        val first = (manager().databaseKey() as Outcome.Ok).value
        dbExists = true
        val second = (manager().databaseKey() as Outcome.Ok).value
        assertArrayEquals(first, second)
    }

    @Test
    fun `missing wrapping key with existing data is an error and creates no new key`() {
        manager().databaseKey()
        dbExists = true
        wrapper.deleteKey(KeyStoreKeyManager.WRAP_ALIAS)
        val blobBefore = store.read(KeyStoreKeyManager.DB_KEY_FILE)!!.copyOf()

        assertEquals(Outcome.Err(KeyError.KeyInvalidated), manager().databaseKey())
        assertArrayEquals(blobBefore, store.read(KeyStoreKeyManager.DB_KEY_FILE))
        assertFalse(wrapper.hasKey(KeyStoreKeyManager.WRAP_ALIAS))
    }

    @Test
    fun `missing key file with existing data is an error`() {
        manager().databaseKey()
        dbExists = true
        store.delete(KeyStoreKeyManager.DB_KEY_FILE)
        assertEquals(Outcome.Err(KeyError.BlobCorrupt), manager().databaseKey())
        assertNull(store.read(KeyStoreKeyManager.DB_KEY_FILE))
    }

    @Test
    fun `damaged key file is an error`() {
        manager().databaseKey()
        dbExists = true
        val blob = store.read(KeyStoreKeyManager.DB_KEY_FILE)!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        store.write(KeyStoreKeyManager.DB_KEY_FILE, blob)
        assertEquals(Outcome.Err(KeyError.BlobCorrupt), manager().databaseKey())
    }

    @Test
    fun `interrupted first setup is completed on next launch`() {
        wrapper.createKey(KeyStoreKeyManager.WRAP_ALIAS)
        store.write(KeyStoreKeyManager.REF_KEY_FILE, byteArrayOf(1, 2, 3))
        assertTrue(manager().databaseKey() is Outcome.Ok)
    }

    @Test
    fun `secrets round trip and deleteAll removes everything`() {
        val km = manager()
        val blob = (km.encryptSecret("app-password".toByteArray()) as Outcome.Ok).value
        assertEquals("app-password", String((km.decryptSecret(blob) as Outcome.Ok).value))

        km.databaseKey()
        km.deleteAll()
        assertNull(store.read(KeyStoreKeyManager.DB_KEY_FILE))
        assertNull(store.read(KeyStoreKeyManager.REF_KEY_FILE))
        assertFalse(wrapper.hasKey(KeyStoreKeyManager.WRAP_ALIAS))
        assertEquals(Outcome.Err(KeyError.KeyInvalidated), km.decryptSecret(blob))
    }
}

/** AES-GCM with in-memory keys, mirroring the Android Keystore wrapper's behaviour. */
private class FakeKeyWrapper : KeyWrapper {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun hasKey(alias: String) = alias in keys

    override fun createKey(alias: String) {
        keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    }

    override fun wrap(alias: String, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keys[alias] ?: throw KeyWrapperException.Invalidated(null))
        return c.iv + c.doFinal(plain)
    }

    override fun unwrap(alias: String, blob: ByteArray): ByteArray {
        val key = keys[alias] ?: throw KeyWrapperException.Invalidated(null)
        if (blob.size < 12 + 16) throw KeyWrapperException.Corrupt(null)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            c.doFinal(blob.copyOfRange(12, blob.size))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw KeyWrapperException.Corrupt(e)
        }
    }

    override fun deleteKey(alias: String) {
        keys.remove(alias)
    }
}

private class InMemoryKeyStore : WrappedKeyStore {
    private val files = mutableMapOf<String, ByteArray>()
    override fun read(name: String) = files[name]?.copyOf()
    override fun write(name: String, blob: ByteArray) {
        files[name] = blob.copyOf()
    }
    override fun delete(name: String) {
        files.remove(name)
    }
}
