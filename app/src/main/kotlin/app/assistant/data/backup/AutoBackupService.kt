package app.assistant.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.AutoBackup
import app.assistant.domain.port.AutoBackupState
import app.assistant.domain.port.BackupError
import app.assistant.domain.port.BackupManager
import app.assistant.domain.port.TimeSource
import app.assistant.security.keys.AndroidKeystoreWrapper
import app.assistant.security.keys.NoBackupWrappedKeyStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoBackupService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val time: TimeSource,
) : AutoBackup {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wrapper by lazy { AndroidKeystoreWrapper() }
    private val keys by lazy { NoBackupWrappedKeyStore(context) }
    private val _state = MutableStateFlow(read())
    override val state: Flow<AutoBackupState> = _state.asStateFlow()

    private fun read(): AutoBackupState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = p.getString(K_URI, null)
        return AutoBackupState(
            enabled = uri != null,
            folderLabel = uri?.let { label(Uri.parse(it)) },
            everyDays = p.getInt(K_DAYS, DEFAULT_DAYS),
            lastAtEpochMs = p.getLong(K_LAST, 0L).takeIf { it > 0 },
            lastOk = if (p.contains(K_OK)) p.getBoolean(K_OK, false) else null,
        )
    }

    private fun label(uri: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "Folder" } }.getOrDefault("Folder")

    override suspend fun enable(folderUri: String, passphrase: CharArray, everyDays: Int) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(folderUri)
        // Keep access to the folder across restarts.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        wrapper.createKey(ALIAS)
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        keys.write(PASS_FILE, wrapper.wrap(ALIAS, bytes))
        bytes.fill(0)
        prefs.edit().putString(K_URI, folderUri).putInt(K_DAYS, everyDays.coerceIn(1, MAX_DAYS)).apply()
        schedule(context, everyDays)
        _state.value = read()
    }

    override suspend fun disable() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        keys.delete(PASS_FILE)
        prefs.edit().clear().apply()
        _state.value = read()
    }

    override suspend fun runNow(): Outcome<Unit, BackupError> = withContext(Dispatchers.IO) {
        val tree = prefs.getString(K_URI, null)?.let(Uri::parse) ?: return@withContext Outcome.Err(BackupError.IO_FAILED)
        val pass = runCatching { keys.read(PASS_FILE)?.let { String(wrapper.unwrap(ALIAS, it), Charsets.UTF_8).toCharArray() } }.getOrNull()
            ?: return@withContext Outcome.Err(BackupError.CANNOT_UNLOCK)
        val result = runCatching {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val stamp = time.now().atZone(time.zone()).format(STAMP)
            val doc = DocumentsContract.createDocument(resolver, parent, MIME, "$PREFIX$stamp$EXT") ?: error("cannot create")
            val r = resolver.openOutputStream(doc)?.use { backup.export(it, pass) } ?: Outcome.Err(BackupError.IO_FAILED)
            if (r is Outcome.Err) runCatching { DocumentsContract.deleteDocument(resolver, doc) } else prune(tree)
            r
        }.getOrElse { Outcome.Err(BackupError.IO_FAILED) }
        pass.fill(' ')
        prefs.edit().putLong(K_LAST, time.now().toEpochMilli()).putBoolean(K_OK, result is Outcome.Ok).apply()
        _state.value = read()
        result
    }

    /** Keeps the newest [KEEP] backups this app wrote; older ones are deleted. */
    private fun prune(tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mine = ArrayList<Pair<String, String>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(EXT)) mine += c.getString(0) to name
            }
        }
        mine.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    /** "Delete everything" forgets the folder, the schedule and the wrapped passphrase. */
    fun wipe() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        keys.delete(PASS_FILE)
        runCatching { wrapper.deleteKey(ALIAS) }
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "auto_backup"
        private const val K_URI = "folder"
        private const val K_DAYS = "every_days"
        private const val K_LAST = "last_at"
        private const val K_OK = "last_ok"
        private const val PASS_FILE = "backup.pass"
        private const val ALIAS = "apex_backup_v1"
        private const val WORK = "auto_backup"
        private const val MIME = "application/octet-stream"
        private const val PREFIX = "apex-backup-"
        private const val EXT = ".paa"
        private const val KEEP = 4
        private const val DEFAULT_DAYS = 7
        private const val MAX_DAYS = 30
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

        fun schedule(context: Context, everyDays: Int) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(everyDays.coerceIn(1, MAX_DAYS).toLong(), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auto: AutoBackup,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (auto.runNow() is Outcome.Ok) Result.success() else Result.retry()
}
