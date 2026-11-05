package app.assistant.testing

import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.DiagnosticSink
import java.time.Instant

/** Collects diagnostic events so tests can assert what was (and was not) logged. */
class RecordingLogSink : DiagnosticSink {
    val events = mutableListOf<DiagnosticEvent>()

    override fun write(at: Instant, event: DiagnosticEvent) {
        events += event
    }

    /** Everything that would reach a log, flattened to one string, for "no content leaked" checks. */
    fun allText(): String = events.joinToString("\n") { e -> e.code + " " + e.detail().entries.joinToString(" ") }
}
