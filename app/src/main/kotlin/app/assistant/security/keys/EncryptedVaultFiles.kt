package app.assistant.security.keys

import android.content.Context
import app.assistant.domain.port.VaultFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The document vault's file store. Each file is sealed with AES-256-GCM under a random vault key;
 * that key is itself wrapped by an Android Keystore key and kept in `noBackupFilesDir`, so neither
 * the files nor the key leave the phone or enter Android backups. "Delete everything" removes
 * the folder and the key.
 *
 * File format: version (1 byte) | IV (12 bytes) | ciphertext with 128-bit tag.
 */
@Singleton
class EncryptedVaultFiles @Inject constructor(@param:ApplicationContext private val context: Context) : VaultFiles {
    private val wrapper: KeyWrapper by lazy { AndroidKeystoreWrapper() }
    private val store: WrappedKeyStore by lazy { NoBackupWrappedKeyStore(context) }
    private val dir: File get() = File(context.noBackupFilesDir, DIR).apply { mkdirs() }
    private val random = SecureRandom()

    @Synchronized
    private fun vaultKey(): ByteArray {
        store.read(KEY_FILE)?.let { return wrapper.unwrap(ALIAS, it) }
        wrapper.createKey(ALIAS)
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        store.write(KEY_FILE, wrapper.wrap(ALIAS, key))
        return key
    }

    override suspend fun put(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(vaultKey(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(bytes)
        val name = UUID.randomUUID().toString() + ".bin"
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(ByteBuffer.allocate(1 + IV_BYTES + sealed.size).put(VERSION).put(iv).put(sealed).array())
        check(tmp.renameTo(File(dir, name))) { "vault write failed" }
        name
    }

    override suspend fun read(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!SAFE_NAME.matches(fileName)) return@withContext null
        val file = File(dir, fileName).takeIf { it.isFile } ?: return@withContext null
        runCatching {
            val all = file.readBytes()
            require(all.size > 1 + IV_BYTES && all[0] == VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(vaultKey(), "AES"), GCMParameterSpec(TAG_BITS, all, 1, IV_BYTES))
            cipher.doFinal(all, 1 + IV_BYTES, all.size - 1 - IV_BYTES)
        }.getOrNull()
    }

    override suspend fun remove(fileName: String) {
        withContext(Dispatchers.IO) { if (SAFE_NAME.matches(fileName)) File(dir, fileName).delete() }
    }

    /** Wipes every vault file and the vault key ("Delete everything"). */
    fun wipe() {
        File(context.noBackupFilesDir, DIR).deleteRecursively()
        store.delete(KEY_FILE)
        runCatching { wrapper.deleteKey(ALIAS) }
    }

    private companion object {
        const val DIR = "vault"
        const val KEY_FILE = "vault.key"
        const val ALIAS = "apex_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        val SAFE_NAME = Regex("^[0-9a-f-]{36}\\.bin$")
    }
}
