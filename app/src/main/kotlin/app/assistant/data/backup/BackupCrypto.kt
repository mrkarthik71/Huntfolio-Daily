package app.assistant.data.backup

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels

/**
 * The only place the export's cryptography lives, so verifying the Tink API on min SDK 26 and the
 * Argon2id cost on the Low_RAM_Profile touches one file (as the design asks). It derives a 32-byte
 * key from the passphrase with Argon2id (Bouncy Castle) and wraps the body with Tink's
 * `AesGcmHkdfStreaming`, which authenticates the header via associated data.
 *
 * VERIFY AT GATE: (1) the AesGcmHkdfStreaming constructor signature and segment size on the pinned
 * Tink version; (2) Argon2BytesGenerator memory/passes feasibility on a 2 GB device; (3) that the
 * streaming channels close/flush correctly so a truncated file is detected on decrypt.
 */
object BackupCrypto {
    private const val KEY_BYTES = 32
    private const val SEGMENT_BYTES = 1 shl 20 // 1 MiB segments
    private const val FIRST_SEGMENT_OFFSET = 0
    private const val HKDF_ALG = "HmacSha256"

    /** Argon2id over [passphrase] with [salt]; returns a 32-byte key. The passphrase is NOT cleared here. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, memoryKib: Int, passes: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(passes)
            .withParallelism(1)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(KEY_BYTES)
        val pwBytes = charsToBytes(passphrase)
        try {
            generator.generateBytes(pwBytes, out)
        } finally {
            pwBytes.fill(0)
        }
        return out
    }

    private fun streaming(key: ByteArray): AesGcmHkdfStreaming = AesGcmHkdfStreaming(key, HKDF_ALG, KEY_BYTES, SEGMENT_BYTES, FIRST_SEGMENT_OFFSET)

    /** Encrypts [plaintext] into [out]; [associatedData] is the header, so tampering fails decrypt. */
    fun encrypt(key: ByteArray, associatedData: ByteArray, plaintext: InputStream, out: OutputStream) {
        val channel = streaming(key).newEncryptingChannel(Channels.newChannel(out), associatedData)
        Channels.newOutputStream(channel).use { plaintext.copyTo(it) }
    }

    /** Decrypts [ciphertext] into [out]; throws if the key is wrong or the header/body was tampered with. */
    fun decrypt(key: ByteArray, associatedData: ByteArray, ciphertext: InputStream, out: OutputStream) {
        val channel = streaming(key).newDecryptingChannel(Channels.newChannel(ciphertext), associatedData)
        Channels.newInputStream(channel).use { it.copyTo(out) }
    }

    private fun charsToBytes(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
