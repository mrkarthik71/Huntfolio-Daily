package app.assistant.data.audit

import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.AuditEntryEntity
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.AuditEntry
import app.assistant.domain.model.AuditId
import app.assistant.domain.model.AuditKind
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.PromptType
import app.assistant.domain.port.AuditError
import app.assistant.domain.port.AuditLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAuditLog @Inject constructor(private val holder: DatabaseHolder) : AuditLog {
    private val dao get() = holder.database().auditDao()

    override suspend fun recordRead(source: DataSource, at: Instant, itemCount: Int, success: Boolean) {
        dao.insert(row(at, AuditKind.READ, source = source, itemCount = itemCount, success = success))
    }

    override suspend fun recordAiSend(at: Instant, mode: AiMode, type: PromptType, payload: String): Outcome<AuditId, AuditError> = try {
        val id = dao.insert(row(at, AuditKind.AI_SEND, source = DataSource.AI_PROVIDER, mode = mode, type = type, payload = payload))
        Outcome.Ok(AuditId(id))
    } catch (e: Exception) {
        Outcome.Err(AuditError.WRITE_FAILED)
    }

    override suspend fun markAiResult(id: AuditId, at: Instant, success: Boolean) {
        dao.insert(row(at, AuditKind.AI_RESULT, source = DataSource.AI_PROVIDER, success = success, parent = id))
    }

    override fun newest(limit: Int): Flow<List<AuditEntry>> = dao.newest(limit).map { rows -> rows.map(::toDomain) }

    private fun row(
        at: Instant,
        kind: AuditKind,
        source: DataSource? = null,
        itemCount: Int? = null,
        success: Boolean? = null,
        mode: AiMode? = null,
        type: PromptType? = null,
        payload: String? = null,
        parent: AuditId? = null,
    ) = AuditEntryEntity(
        at = at.toEpochMilli(),
        kind = kind.name,
        source = source?.name,
        itemCount = itemCount,
        success = success,
        aiMode = mode?.name,
        promptType = type?.name,
        payload = payload,
        parentId = parent?.value,
    )

    companion object {
        fun toDomain(e: AuditEntryEntity) = AuditEntry(
            id = AuditId(e.id),
            at = Instant.ofEpochMilli(e.at),
            kind = AuditKind.valueOf(e.kind),
            source = e.source?.let { s -> DataSource.entries.firstOrNull { it.name == s } },
            itemCount = e.itemCount,
            success = e.success,
            aiMode = e.aiMode?.let { s -> AiMode.entries.firstOrNull { it.name == s } },
            promptType = e.promptType?.let { s -> PromptType.entries.firstOrNull { it.name == s } },
            payload = e.payload,
            parentId = e.parentId?.let(::AuditId),
        )
    }
}
