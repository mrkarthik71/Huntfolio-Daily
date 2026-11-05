// The one place allowed to use android.util.Log: the debug-only Logcat sink below.
@file:Suppress("ForbiddenImport")

package app.assistant.security.log

import android.util.Log
import app.assistant.domain.port.TimeSource
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/** Where diagnostic events go. The Room-backed ring buffer is one sink (task 3.3). */
fun interface DiagnosticSink {
    fun write(at: Instant, event: DiagnosticEvent)
}

/**
 * The only logging API in the app. It accepts [DiagnosticEvent]s and nothing else; there is no
 * method that takes a String, so Message_Text cannot be logged by mistake (Requirement 3.5).
 * A failing sink never breaks the caller.
 */
@Singleton
class SafeLog @Inject constructor(private val time: TimeSource) {
    private val sinks = CopyOnWriteArrayList<DiagnosticSink>()

    fun addSink(sink: DiagnosticSink) {
        sinks += sink
    }

    fun removeSink(sink: DiagnosticSink) {
        sinks -= sink
    }

    fun record(event: DiagnosticEvent) {
        val at = time.now()
        for (sink in sinks) {
            runCatching { sink.write(at, event) }
        }
    }

    fun error(component: Component, t: Throwable) = record(DiagnosticEvent.ErrorCaught(component, ErrorFingerprint.of(t)))
}

/**
 * Debug-only Logcat output of codes and enum/number details. Release builds strip
 * android.util.Log calls with R8, and this sink is registered only in debug builds.
 */
class LogcatSink : DiagnosticSink {
    override fun write(at: Instant, event: DiagnosticEvent) {
        Log.d(TAG, event.code + " " + event.detail().entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    private companion object {
        const val TAG = "Assistant"
    }
}
