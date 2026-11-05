package app.assistant.ui.transfer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.port.BackupError
import app.assistant.domain.port.CareerFileReader
import app.assistant.domain.engine.backup.ExportPassphrase
import app.assistant.domain.error.Outcome
import app.assistant.domain.career.CareerImporter
import app.assistant.domain.career.HuntfolioError
import app.assistant.domain.career.HuntfolioImport
import app.assistant.domain.career.ImportPreview
import app.assistant.domain.career.ImportResult
import app.assistant.domain.career.SkillChoice
import app.assistant.domain.port.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransferUi(
    val busy: Boolean = false,
    val parsedImport: HuntfolioImport? = null,
    val preview: ImportPreview? = null,
    val importResult: ImportResult? = null,
    val importError: String? = null,
    val exportDone: Boolean = false,
    val restoreDone: Boolean = false,
    val passphraseError: String? = null,
    val backupError: String? = null,
)

@HiltViewModel
class DataTransferViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val reader: CareerFileReader,
    private val importer: CareerImporter,
    private val backup: BackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow(TransferUi())
    val state: StateFlow<TransferUi> = _state.asStateFlow()

    /** Reads + previews a picked Huntfolio file (Requirement 59.1, 59.2). */
    fun previewImport(uri: Uri) {
        viewModelScope.launch {
            _state.value = TransferUi(busy = true)
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            val result = context.contentResolver.openInputStream(uri)?.use { reader.read(it, size) }
            when (result) {
                is Outcome.Ok -> _state.value = TransferUi(parsedImport = result.value, preview = importer.preview(result.value))
                is Outcome.Err -> _state.value = TransferUi(importError = importMessage(result.error))
                null -> _state.value = TransferUi(importError = "Could not open the file.")
            }
        }
    }

    /** Commits the previewed import with the chosen skills (Requirement 59.4, 59.9). */
    fun commitImport(skills: List<SkillChoice>) {
        val import = _state.value.parsedImport ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = importer.commit(import, skills)
            _state.value = _state.value.copy(busy = false, importResult = result)
        }
    }

    /** Exports an encrypted backup to [uri] after validating the two passphrase entries (Requirement 60.1). */
    fun export(uri: Uri, passphrase: CharArray, repeat: CharArray) {
        ExportPassphrase.validate(passphrase, repeat)?.let {
            _state.value = _state.value.copy(passphraseError = passphraseMessage(it))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, passphraseError = null, backupError = null)
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                _state.value = _state.value.copy(busy = false, backupError = "Could not open the destination.")
                return@launch
            }
            val result = out.use { backup.export(it, passphrase) }
            _state.value = when (result) {
                is Outcome.Ok -> _state.value.copy(busy = false, exportDone = true)
                is Outcome.Err -> _state.value.copy(busy = false, backupError = backupMessage(result.error))
            }
        }
    }

    /** Restores from a picked export file with the entered passphrase (Requirement 60.5, 60.6). */
    fun restore(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, backupError = null)
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                _state.value = _state.value.copy(busy = false, backupError = "Could not open the file.")
                return@launch
            }
            val result = input.use { backup.restore(it, passphrase) }
            _state.value = when (result) {
                is Outcome.Ok -> _state.value.copy(busy = false, restoreDone = true)
                is Outcome.Err -> _state.value.copy(busy = false, backupError = backupMessage(result.error))
            }
        }
    }

    private fun importMessage(error: HuntfolioError): String = when (error) {
        HuntfolioError.TooLarge -> "The file is larger than 5 MB."
        HuntfolioError.NotJson -> "The file is not valid Huntfolio JSON."
        HuntfolioError.MissingVersion -> "The file has no format version."
        is HuntfolioError.UnsupportedVersion -> "Unsupported Huntfolio version ${error.found}."
        is HuntfolioError.FirstInvalidRecord -> "Record ${error.index + 1} is invalid: ${error.reason}."
    }

    private fun passphraseMessage(error: ExportPassphrase.Error): String = when (error) {
        ExportPassphrase.Error.TOO_SHORT -> "Use at least ${ExportPassphrase.MIN_LENGTH} characters."
        ExportPassphrase.Error.MISMATCH -> "The two passphrases do not match."
    }

    private fun backupMessage(error: BackupError): String = when (error) {
        BackupError.CANNOT_UNLOCK -> "The passphrase did not unlock this backup, or it was tampered with."
        BackupError.NOT_AN_EXPORT -> "This file is not a backup."
        BackupError.IO_FAILED -> "The backup could not be written or read."
    }
}
