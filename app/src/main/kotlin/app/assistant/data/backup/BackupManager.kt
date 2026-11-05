package app.assistant.data.backup

import app.assistant.domain.error.Outcome
import java.io.InputStream
import java.io.OutputStream

/**
 * The encrypted Export_File format (Requirement 60, design: Backup_Manager). A clear header carries
 * the magic bytes, the KDF parameters and the AEAD identifier; the header bytes are the AEAD
 * associated data, so tampering with them fails decryption (Requirement 60.3). The ciphertext is a
 * ZIP of `data.jsonl`, the settings and the Source_Ref key — the `secret` table is NEVER exported
 * (Requirement 67.5), so mail/AI credentials never leave the device in a backup.
 *
 * Crypto: the passphrase is stretched with Argon2id (Bouncy Castle: 64 MiB, 3 passes — measured on
 * the Low_RAM_Profile) and the derived key feeds Tink's AesGcmHkdfStreaming for the body. The
 * passphrase is handled as a CharArray and cleared after use.
 *
 * NOTE: the exact Tink streaming-AEAD construction and the Argon2 cost must be verified against the
 * min-SDK-26 runtime at the build/test gate; this file pins the format and flow, and the crypto
 * calls are isolated in [BackupCrypto] so that verification touches one place.
 */
data class ExportHeader(
    val magic: String,
    val version: Int,
    val kdf: String,
    val argonMemoryKib: Int,
    val argonPasses: Int,
    val salt: ByteArray,
    val aead: String,
) {
    /** The header serialised to bytes, used verbatim as the AEAD associated data. */
    fun associatedData(): ByteArray = buildString {
        append(magic).append('|').append(version).append('|').append(kdf).append('|')
        append(argonMemoryKib).append('|').append(argonPasses).append('|').append(aead).append('|')
        append(java.util.Base64.getEncoder().encodeToString(salt))
    }.toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is ExportHeader && other.associatedData().contentEquals(associatedData())
    override fun hashCode(): Int = associatedData().contentHashCode()

    companion object {
        const val MAGIC = "PAAEXPORT"
        const val VERSION = 1
        const val KDF = "argon2id"
        const val AEAD = "AES256-GCM-HKDF-STREAMING"
        const val ARGON_MEMORY_KIB = 64 * 1024
        const val ARGON_PASSES = 3
        const val SALT_BYTES = 16
    }
}


/**
 * Supplies and consumes the logical backup payload, so the Backup_Manager stays schema-agnostic.
 * The dump is `data.jsonl` (one JSON row per line across the exported tables) plus the settings
 * bytes and the Source_Ref key; the `secret` table is NEVER included (Requirement 67.5). Restore
 * loads a staging database from the payload; the manager then does the atomic rename.
 */
interface BackupDataSource {
    /** Writes `data.jsonl` for every exported table (secret table excluded) to [out]. */
    suspend fun dumpData(out: OutputStream)

    /** The current settings bytes (non-personal config). */
    suspend fun dumpSettings(): ByteArray

    /** The Source_Ref key bytes, so restored data keeps matching refs (Requirement 60.4). */
    suspend fun dumpRefKey(): ByteArray

    /** Loads the payload into a fresh STAGING database file and returns its path for the atomic rename. */
    suspend fun loadToStaging(data: InputStream, settings: ByteArray, refKey: ByteArray): String

    /** Swaps the staging database into place atomically and reopens (Requirement 60.5, 60.7). */
    suspend fun promoteStaging(stagingPath: String)

    /** True when a half-finished promotion is detected on launch. */
    fun hasInterruptedRestore(): Boolean

    /** Finishes an interrupted promotion. */
    suspend fun finishInterruptedRestore()
}
