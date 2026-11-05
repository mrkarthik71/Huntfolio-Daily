package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.assistant.data.db.entity.BillProposalEntity
import app.assistant.data.db.entity.MoneyAppEntity
import app.assistant.data.db.entity.ProcessedRefEntity
import app.assistant.data.db.entity.SenderEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SenderDao {
    @Query("SELECT * FROM sender_entry WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<SenderEntryEntity>
}

@Dao
interface MoneyAppDao {
    @Query("SELECT COUNT(*) FROM money_app WHERE package_name = :packageName")
    suspend fun count(packageName: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(app: MoneyAppEntity): Long

    @Query("DELETE FROM money_app WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT package_name FROM money_app ORDER BY package_name")
    fun observe(): Flow<List<String>>
}

@Dao
interface LedgerDao {
    @Query("SELECT COUNT(*) FROM processed_source_ref WHERE source_ref = :ref")
    suspend fun processedCount(ref: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(row: ProcessedRefEntity): Long
}

@Dao
interface BillProposalDao {
    /** `IS` compares NULLs as equal, so a missing Merchant or due date matches another missing one. */
    @Query("SELECT COUNT(*) FROM bill_proposal WHERE merchant_id IS :merchant AND amount_paise = :amount AND due_date IS :dueDate AND status = 'OPEN'")
    suspend fun openCount(merchant: Long?, amount: Long, dueDate: String?): Int

    @Query("SELECT COUNT(*) FROM bill WHERE merchant_id IS :merchant AND amount_paise = :amount AND due_date IS :dueDate")
    suspend fun billCount(merchant: Long?, amount: Long, dueDate: String?): Int

    @Insert
    suspend fun insert(row: BillProposalEntity): Long
}
