package app.assistant.data.repo

import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.MemoryNoteEntity
import app.assistant.domain.port.MemoryNote
import app.assistant.domain.port.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

/** Room implementation of [MemoryRepository]; notes live in the encrypted store with everything else. */
class RoomMemoryRepository @Inject constructor(private val holder: DbProvider) : MemoryRepository {
    private val dao get() = holder.database().apexDao()

    override fun notes(): Flow<List<MemoryNote>> = dao.notes().map { l -> l.map { MemoryNote(it.id, it.text, Instant.ofEpochMilli(it.createdAt), it.pinned) } }

    override suspend fun add(text: String, at: Instant): Long =
        dao.insertNote(MemoryNoteEntity(text = text.trim().take(MemoryRepository.MAX_LENGTH), createdAt = at.toEpochMilli()))

    override suspend fun setPinned(id: Long, pinned: Boolean) = dao.setNotePinned(id, pinned)

    override suspend fun delete(id: Long) = dao.deleteNote(id)
}
