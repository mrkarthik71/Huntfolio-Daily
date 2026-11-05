package app.assistant.security.keys

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM wrapping keys in the Android Keystore. Keys are not bound to user
 * authentication (ADR 0003) and use StrongBox when the device has it.
 *
 * Blob format: version (1 byte) | IV length (1 byte) | IV | ciphertext with 128-bit tag.
 */
class AndroidKeystoreWrapper : KeyWrapper {

    private val keyStore: KeyStore by lazy {
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (e: GeneralSecurityException) {
            throw KeyWrapperException.Unavailable(e)
        } catch (e: java.io.IOException) {
            throw KeyWrapperException.Unavailable(e)
        }
    }

    override fun hasKey(alias: String): Boolean = mapErrors { keyStore.containsAlias(alias) }

    override fun createKey(alias: String) = mapErrors {
        if (keyStore.containsAlias(alias)) return@mapErrors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(alias, strongBox = true)
                return@mapErrors
            } catch (e: StrongBoxUnavailableException) {
                // Fall through to a TEE-backed key.
            } catch (e: ProviderException) {
                // Some devices report missing StrongBox this way.
            }
        }
        generate(alias, strongBox = false)
    }

    override fun wrap(alias: String, plain: ByteArray): ByteArray = mapErrors {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        ByteBuffer.allocate(2 + iv.size + sealed.size)
            .put(BLOB_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(sealed)
            .array()
    }

    override fun unwrap(alias: String, blob: ByteArray): ByteArray = mapErrors {
        if (blob.size < 2 || blob[0] != BLOB_VERSION) throw KeyWrapperException.Corrupt(null)
        val ivLength = blob[1].toInt()
        if (ivLength <= 0 || blob.size < 2 + ivLength + TAG_BYTES) throw KeyWrapperException.Corrupt(null)
        val iv = blob.copyOfRange(2, 2 + ivLength)
        val sealed = blob.copyOfRange(2 + ivLength, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.doFinal(sealed)
    }

    override fun deleteKey(alias: String) = mapErrors {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(alias: String): SecretKey = (keyStore.getKey(alias, null) as? SecretKey) ?: throw KeyWrapperException.Invalidated(null)

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(spec)
            generateKey()
        }
    }

    private inline fun <T> mapErrors(block: () -> T): T = try {
        block()
    } catch (e: KeyWrapperException) {
        throw e
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw KeyWrapperException.Invalidated(e)
    } catch (e: AEADBadTagException) {
        throw KeyWrapperException.Corrupt(e)
    } catch (e: GeneralSecurityException) {
        throw KeyWrapperException.Unavailable(e)
    } catch (e: ProviderException) {
        throw KeyWrapperException.Unavailable(e)
    } catch (e: IllegalArgumentException) {
        throw KeyWrapperException.Corrupt(e)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val BLOB_VERSION: Byte = 1
        const val TAG_BYTES = 16
    }
}

/** Wrapped key blobs in `noBackupFilesDir`, which Android never includes in backups. */
class NoBackupWrappedKeyStore(context: Context) : WrappedKeyStore {
    private val dir: File = context.noBackupFilesDir

    override fun read(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, blob: ByteArray) {
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(blob)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not store wrapped key" }
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
        File(dir, "$name.tmp").delete()
    }
}
