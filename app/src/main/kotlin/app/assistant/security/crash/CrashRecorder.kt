package app.assistant.security.crash

import android.content.Context
import android.os.Build
import app.assistant.BuildConfig
import app.assistant.domain.port.CrashReports
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the last crash to a private file (never backed up, never sent). It keeps exception class
 * names and code locations only: exception messages are dropped because they can hold user data
 * (CLAUDE.md: keep facts, not messages).
 */
@Singleton
class CrashRecorder @Inject constructor(@param:ApplicationContext private val context: Context, private val time: TimeSource) : CrashReports {
    private val file get() = File(context.noBackupFilesDir, FILE_NAME)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(format(thread.name, error))
                // Marks it as new, so the next launch shows it before anything else (see [unseen]).
                File(context.noBackupFilesDir, UNSEEN_FILE).writeText("1")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** For errors caught in background work: recorded the same way, without stopping the app. */
    fun record(error: Throwable) {
        runCatching { file.writeText(format(Thread.currentThread().name, error)) }
    }

    override fun last(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    override fun clear() {
        runCatching { file.delete() }
    }

    private fun format(threadName: String, error: Throwable): String = buildString {
        appendLine("Apex ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · ${time.now()} · thread $threadName")
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_CAUSES) {
            appendLine(if (depth == 0) e.javaClass.name else "Caused by ${e.javaClass.name}")
            e.stackTrace.take(MAX_FRAMES).forEach { f -> appendLine("  at ${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber})") }
            e = e.cause
            depth++
        }
    }

    companion object {
        private const val FILE_NAME = "last_crash.txt"
        private const val UNSEEN_FILE = "crash_unseen"

        /** The report of a crash the User has not seen yet, read without Hilt so it works even when startup is what crashes. */
        fun unseen(context: Context): String? = runCatching {
            if (!File(context.noBackupFilesDir, UNSEEN_FILE).exists()) return null
            File(context.noBackupFilesDir, FILE_NAME).takeIf { it.exists() }?.readText()
        }.getOrNull()

        fun markSeen(context: Context) {
            runCatching { File(context.noBackupFilesDir, UNSEEN_FILE).delete() }
        }
        private const val MAX_FRAMES = 30
        private const val MAX_CAUSES = 5
    }
}
