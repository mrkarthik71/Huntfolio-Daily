package app.assistant.data.backup

import app.assistant.domain.port.ExportInfo
import app.assistant.domain.port.BackupManager
import app.assistant.domain.port.BackupError
import android.content.Context
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements the encrypted Export_File (Requirement 60). Export: write the clear header, derive the
 * key with Argon2id, then stream-encrypt a ZIP of `data.jsonl` + settings + ref key with Tink; the
 * `secret` table is never in the payload. The write goes to a temp file that is deleted on any
 * failure or cancel. Restore: read the header, derive the key, decrypt, unzip into a STAGING
 * database, then atomically promote it. An interrupted promotion is finished on launch.
 */
@Singleton
class AndroidBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val data: BackupDataSource,
    private val dispatchers: DispatcherProvider,
) : BackupManager {

    override suspend fun export(out: OutputStream, passphrase: CharArray): Outcome<Unit, BackupError> = withContext(dispatchers.io) {
        val temp = File.createTempFile("export", ".tmp", context.cacheDir)
        try {
            val salt = ByteArray(ExportHeader.SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val header = ExportHeader(ExportHeader.MAGIC, ExportHeader.VERSION, ExportHeader.KDF, ExportHeader.ARGON_MEMORY_KIB, ExportHeader.ARGON_PASSES, salt, ExportHeader.AEAD)
            val key = BackupCrypto.deriveKey(passphrase, salt, header.argonMemoryKib, header.argonPasses)
            try {
                temp.outputStream().use { file ->
                    writeHeader(file, header)
                    BackupCrypto.encrypt(key, header.associatedData(), zipPayload(), file)
                }
                // Only now copy the complete temp file to the caller's stream.
                temp.inputStream().use { it.copyTo(out) }
                Outcome.Ok(Unit)
            } finally {
                key.fill(0)
            }
        } catch (_: Exception) {
            Outcome.Err(BackupError.IO_FAILED)
        } finally {
            passphrase.fill('\u0000')
            temp.delete()
        }
    }

    override suspend fun inspect(input: InputStream): Outcome<ExportInfo, BackupError> = withContext(dispatchers.io) {
        try {
            val header = readHeader(input) ?: return@withContext Outcome.Err(BackupError.NOT_AN_EXPORT)
            Outcome.Ok(ExportInfo(version = header.version, createdAtEpochMs = 0))
        } catch (_: Exception) {
            Outcome.Err(BackupError.NOT_AN_EXPORT)
        }
    }

    override suspend fun restore(input: InputStream, passphrase: CharArray): Outcome<Unit, BackupError> = withContext(dispatchers.io) {
        try {
            val header = readHeader(input) ?: return@withContext Outcome.Err(BackupError.NOT_AN_EXPORT)
            val key = BackupCrypto.deriveKey(passphrase, header.salt, header.argonMemoryKib, header.argonPasses)
            val plain = ByteArrayOutputStream()
            try {
                BackupCrypto.decrypt(key, header.associatedData(), input, plain)
            } catch (_: Exception) {
                // Wrong passphrase or tampered file: Tink fails authentication (Requirement 60.3).
                return@withContext Outcome.Err(BackupError.CANNOT_UNLOCK)
            } finally {
                key.fill(0)
            }
            val (dataBytes, settings, refKey) = unzipPayload(plain.toByteArray()) ?: return@withContext Outcome.Err(BackupError.CANNOT_UNLOCK)
            val staging = data.loadToStaging(ByteArrayInputStream(dataBytes), settings, refKey)
            data.promoteStaging(staging)
            Outcome.Ok(Unit)
        } catch (_: Exception) {
            Outcome.Err(BackupError.IO_FAILED)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun recoverIfInterrupted(): Boolean = withContext(dispatchers.io) {
        if (!data.hasInterruptedRestore()) return@withContext false
        data.finishInterruptedRestore()
        true
    }

    private fun writeHeader(out: OutputStream, header: ExportHeader) {
        val bytes = header.associatedData()
        // 4-byte big-endian length prefix, then the header bytes, so the reader knows where the body starts.
        out.write(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        out.write(bytes)
    }

    private fun readHeader(input: InputStream): ExportHeader? {
        val len = ByteArray(4).let { if (input.read(it) != 4) return null else (it[0].toInt() and 0xff shl 24) or (it[1].toInt() and 0xff shl 16) or (it[2].toInt() and 0xff shl 8) or (it[3].toInt() and 0xff) }
        if (len <= 0 || len > MAX_HEADER) return null
        val bytes = ByteArray(len)
        if (input.read(bytes) != len) return null
        val parts = String(bytes, Charsets.UTF_8).split('|')
        if (parts.size != HEADER_PARTS || parts[0] != ExportHeader.MAGIC) return null
        return ExportHeader(
            magic = parts[0],
            version = parts[1].toIntOrNull() ?: return null,
            kdf = parts[2],
            argonMemoryKib = parts[3].toIntOrNull() ?: return null,
            argonPasses = parts[4].toIntOrNull() ?: return null,
            aead = parts[5],
            salt = java.util.Base64.getDecoder().decode(parts[6]),
        )
    }

    private suspend fun zipPayload(): InputStream {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY)); data.dumpData(zip); zip.closeEntry()
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY)); zip.write(data.dumpSettings()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(REFKEY_ENTRY)); zip.write(data.dumpRefKey()); zip.closeEntry()
        }
        return ByteArrayInputStream(buffer.toByteArray())
    }

    private fun unzipPayload(bytes: ByteArray): Triple<ByteArray, ByteArray, ByteArray>? {
        var data: ByteArray? = null
        var settings: ByteArray? = null
        var refKey: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val content = zip.readBytes()
                when (entry.name) {
                    DATA_ENTRY -> data = content
                    SETTINGS_ENTRY -> settings = content
                    REFKEY_ENTRY -> refKey = content
                }
                entry = zip.nextEntry
            }
        }
        return Triple(data ?: return null, settings ?: return null, refKey ?: return null)
    }

    private companion object {
        const val DATA_ENTRY = "data.jsonl"
        const val SETTINGS_ENTRY = "settings.pb"
        const val REFKEY_ENTRY = "refkey.bin"
        const val MAX_HEADER = 4096
        const val HEADER_PARTS = 7
    }
}
