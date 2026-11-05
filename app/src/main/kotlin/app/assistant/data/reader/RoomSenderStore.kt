package app.assistant.data.reader

import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.SenderEntryEntity
import app.assistant.domain.model.SenderEntry
import app.assistant.domain.port.AddSenderResult
import app.assistant.domain.port.SenderRow
import app.assistant.domain.port.SenderRules
import app.assistant.domain.port.SenderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The Bank_Sender_List in the Encrypted_Store: shipped rows plus the User's additions (Requirement 26.4). */
@Singleton
class RoomSenderStore @Inject constructor(private val holder: DbProvider) : SenderStore {
    override fun senders(): Flow<List<SenderRow>> = holder.database().customSenderDao().observe().map { rows ->
        rows.mapNotNull { row ->
            runCatching { SenderRow(SenderEntry(row.core, row.prefixPattern, row.suffixPattern), row.builtin) }.getOrNull()
        }
    }

    override suspend fun addCustom(rawHeader: String): AddSenderResult {
        val core = SenderRules.normalise(rawHeader) ?: return AddSenderResult.INVALID
        val dao = holder.database().customSenderDao()
        if (dao.count(core) > 0) return AddSenderResult.ALREADY_LISTED
        dao.insert(
            SenderEntryEntity(
                core = core,
                prefixPattern = SenderRules.CUSTOM_PREFIX,
                suffixPattern = SenderRules.CUSTOM_SUFFIX,
                builtin = false,
                enabled = true,
            ),
        )
        return AddSenderResult.ADDED
    }

    override suspend fun removeCustom(coreHeader: String) {
        holder.database().customSenderDao().deleteCustom(coreHeader.trim().uppercase(Locale.ROOT))
    }
}
