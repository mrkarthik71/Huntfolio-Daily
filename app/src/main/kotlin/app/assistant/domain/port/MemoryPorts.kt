package app.assistant.domain.port

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Something the User asked Apex to remember, in their own words. */
data class MemoryNote(val id: Long, val text: String, val createdAt: Instant, val pinned: Boolean)

/** Local store of the User's memory notes ("Ask Apex: remember ..."). */
interface MemoryRepository {
    fun notes(): Flow<List<MemoryNote>>

    suspend fun add(text: String, at: Instant): Long

    suspend fun setPinned(id: Long, pinned: Boolean)

    suspend fun delete(id: Long)

    companion object {
        const val MAX_LENGTH = 500
    }
}
