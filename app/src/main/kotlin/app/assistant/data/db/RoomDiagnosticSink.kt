package app.assistant.data.db

import app.assistant.data.db.entity.DiagnosticEventEntity
import app.assistant.domain.port.ApplicationScope
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.DiagnosticSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the last [CAPACITY] diagnostic events in the Encrypted_Store, for the developer
 * section of Settings. Events before the database is open are dropped.
 */
@Singleton
class RoomDiagnosticSink @Inject constructor(private val holder: DatabaseHolder, @param:ApplicationScope private val scope: CoroutineScope) : DiagnosticSink {

    override fun write(at: Instant, event: DiagnosticEvent) {
        val db = holder.databaseIfOpen() ?: return
        val row = DiagnosticEventEntity(
            at = at.toEpochMilli(),
            code = event.code,
            detail = event.detail().entries.joinToString(";") { "${it.key}=${it.value}" },
        )
        scope.launch { runCatching { db.diagnosticDao().insertAndTrim(row, CAPACITY) } }
    }

    companion object {
        const val CAPACITY = 1_000
    }
}
