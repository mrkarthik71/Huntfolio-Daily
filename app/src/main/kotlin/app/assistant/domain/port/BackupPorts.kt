package app.assistant.domain.port

import app.assistant.domain.error.Outcome
import java.io.InputStream
import java.io.OutputStream

/** Why an export operation failed (Requirement 60.3, 60.8). */
enum class BackupError {
    /** The passphrase did not unlock the file, or the file (or header) was tampered with. */
    CANNOT_UNLOCK,

    /** The file is not a recognised Export_File. */
    NOT_AN_EXPORT,

    /** An IO error writing or reading; a partial export file is deleted (Requirement 60.8). */
    IO_FAILED,
}

/** What an [BackupManager.inspect] reveals without unlocking: the header facts for the restore screen. */
data class ExportInfo(val version: Int, val createdAtEpochMs: Long)

/**
 * Exports and restores the encrypted backup (Requirement 60). The implementation writes via a temp
 * file and deletes it on failure or cancel, restores through a staging database with a
 * `restore.marker` and an atomic rename, and resumes an interrupted restore on launch.
 */
interface BackupManager {
    /** Writes an encrypted export to [out]; a partial file is cleaned up on failure (Requirement 60.8). */
    suspend fun export(out: OutputStream, passphrase: CharArray): Outcome<Unit, BackupError>

    /** Reads only the clear header, so the restore screen can show the created date (Requirement 60.6). */
    suspend fun inspect(input: InputStream): Outcome<ExportInfo, BackupError>

    /** Restores from [input] through a staging database and atomic rename (Requirement 60.5, 60.7). */
    suspend fun restore(input: InputStream, passphrase: CharArray): Outcome<Unit, BackupError>

    /** On launch, finishes a restore interrupted mid-rename (Requirement 60.7). */
    suspend fun recoverIfInterrupted(): Boolean
}
