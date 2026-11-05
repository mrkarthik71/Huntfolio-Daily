package app.assistant.data.ai

import app.assistant.data.db.DatabaseHolder
import app.assistant.domain.port.CapStore
import javax.inject.Inject
import javax.inject.Singleton

/** [CapStore] over the `ai_usage` table (design: Cap_Governor). All accumulation is atomic in the DAO. */
@Singleton
class RoomCapStore @Inject constructor(private val holder: DatabaseHolder) : CapStore {
    private val dao get() = holder.database().aiUsageDao()

    override suspend fun tryReserve(month: String, paise: Long, capPaise: Long): Boolean = dao.tryReserve(month, paise, capPaise)

    override suspend fun recordAttempt(month: String, extraPaise: Long) = dao.add(month, extraPaise, attempts = 1)

    override suspend fun spent(month: String): Long = dao.spent(month) ?: 0

    override suspend fun attempts(month: String): Int = dao.attempts(month) ?: 0
}
