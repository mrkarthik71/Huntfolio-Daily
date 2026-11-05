package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.BillProposalEntity
import app.assistant.data.db.entity.MoneyAppEntity
import app.assistant.data.db.entity.ProcessedRefEntity
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SenderEntry
import app.assistant.domain.model.SourceRef
import app.assistant.domain.port.AddAppResult
import app.assistant.domain.port.AtomicWork
import app.assistant.domain.port.BillProposals
import app.assistant.domain.port.IngestLedger
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.MoneyAppRules
import app.assistant.domain.port.MoneyAppStore
import app.assistant.domain.port.RefState
import app.assistant.domain.port.SenderList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Shipped and User-added senders that are switched on (Requirement 26.4). An entry that cannot be used is skipped, not fatal. */
@Singleton
class RoomSenderList @Inject constructor(private val holder: DbProvider) : SenderList {
    override suspend fun entries(): List<SenderEntry> = holder.database().senderDao().enabled().mapNotNull { row ->
        runCatching { SenderEntry(row.core, row.prefixPattern, row.suffixPattern) }.getOrNull()
    }
}

@Singleton
class RoomMoneyAppList @Inject constructor(private val holder: DbProvider) : MoneyAppList {
    override suspend fun contains(packageName: String): Boolean = holder.database().moneyAppDao().count(packageName) > 0
}

/** Adds and removes apps. Removing deletes only the list entry: no Transaction, fact or Merchant is touched (Requirement 27.7). */
@Singleton
class RoomMoneyAppStore @Inject constructor(private val holder: DbProvider) : MoneyAppStore {
    override fun apps(): Flow<Set<String>> = holder.database().moneyAppDao().observe().map { it.toSet() }

    override suspend fun add(packageName: String): AddAppResult {
        val name = packageName.trim()
        if (!MoneyAppRules.isValidPackage(name)) return AddAppResult.INVALID
        return if (holder.database().moneyAppDao().insert(MoneyAppEntity(name)) == IGNORED) AddAppResult.ALREADY_LISTED else AddAppResult.ADDED
    }

    override suspend fun remove(packageName: String) {
        holder.database().moneyAppDao().delete(packageName.trim())
    }

    private companion object {
        const val IGNORED = -1L
    }
}

/** Tombstones come first: a message the User deleted or dismissed stays gone even if it was also processed. */
@Singleton
class RoomIngestLedger @Inject constructor(private val holder: DbProvider) : IngestLedger {
    override suspend fun state(ref: SourceRef): RefState {
        val db = holder.database()
        return when {
            db.transactionDao().tombstoneCount(ref.value) > 0 -> RefState.TOMBSTONED
            db.ledgerDao().processedCount(ref.value) > 0 -> RefState.PROCESSED
            else -> RefState.NEW
        }
    }

    override suspend fun markProcessed(ref: SourceRef, outcome: String, at: Instant) {
        holder.database().ledgerDao().put(ProcessedRefEntity(ref.value, outcome, at.toEpochMilli()))
    }
}

@Singleton
class RoomBillProposals @Inject constructor(private val holder: DbProvider) : BillProposals {
    override suspend fun propose(merchant: String?, amount: Paise, dueDate: LocalDate?): Boolean {
        val db = holder.database()
        return db.withTransaction {
            val merchantId = MerchantResolver(db.merchantCatalogDao()).resolve(merchant)
            val due = dueDate?.toString()
            val dao = db.billProposalDao()
            // The same Merchant, amount and due date as a Bill or an open proposal makes no new proposal (Requirement 34.8).
            if (dao.billCount(merchantId, amount.value, due) > 0 || dao.openCount(merchantId, amount.value, due) > 0) return@withTransaction false
            dao.insert(BillProposalEntity(merchantId = merchantId, amountPaise = amount.value, dueDate = due, status = OPEN))
            true
        }
    }

    private companion object {
        const val OPEN = "OPEN"
    }
}

@Singleton
class RoomAtomicWork @Inject constructor(private val holder: DbProvider) : AtomicWork {
    override suspend fun <T> run(block: suspend () -> T): T = holder.database().withTransaction { block() }
}
