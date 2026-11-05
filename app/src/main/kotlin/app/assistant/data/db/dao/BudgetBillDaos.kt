package app.assistant.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.assistant.data.db.entity.BillEntity
import app.assistant.data.db.entity.BillPaidProposalEntity
import app.assistant.data.db.entity.BudgetEntity
import app.assistant.data.db.entity.QualityCounterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget ORDER BY category_id")
    fun observe(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget ORDER BY category_id")
    suspend fun all(): List<BudgetEntity>

    /** A category has one Budget, so a second save for it replaces the first (Requirement 33.1). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(budget: BudgetEntity)

    @Query("DELETE FROM budget WHERE category_id = :category")
    suspend fun delete(category: Long)

    @Query("SELECT COUNT(*) FROM category WHERE id = :category")
    suspend fun categoryCount(category: Long): Int
}

/** A category id and name, for the spending reports. */
data class CategoryRow(val id: Long, val name: String)

@Dao
interface CategoryDao {
    @Query("SELECT id, name FROM category")
    suspend fun all(): List<CategoryRow>
}

/** A proposal with its Merchant's name for display. */
data class BillProposalRow(
    val id: Long,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "merchant_name") val merchantName: String?,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    @ColumnInfo(name = "due_date") val dueDate: String?,
)

@Dao
interface BillDao {
    @Insert
    suspend fun insert(bill: BillEntity): Long

    @Update
    suspend fun update(bill: BillEntity)

    @Query("UPDATE bill SET series_id = :series WHERE id = :id")
    suspend fun setSeries(id: Long, series: Long)

    @Query("SELECT * FROM bill WHERE id = :id")
    suspend fun get(id: Long): BillEntity?

    @Query("SELECT * FROM bill ORDER BY paid, due_date, id")
    fun observe(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bill WHERE paid = 0 ORDER BY due_date, id")
    suspend fun unpaid(): List<BillEntity>

    @Query("SELECT * FROM bill WHERE paid = 0 AND due_date BETWEEN :from AND :to ORDER BY due_date, id")
    suspend fun unpaidBetween(from: String, to: String): List<BillEntity>

    @Query("UPDATE bill SET paid = 1 WHERE id = :id")
    suspend fun setPaid(id: Long)

    @Query("UPDATE bill SET notified = 1 WHERE id = :id")
    suspend fun setNotified(id: Long)

    @Query("DELETE FROM bill WHERE id = :id")
    suspend fun delete(id: Long)

    // Bill proposals made from bill messages (written by BillProposals in the ingest stores).

    @Query(
        "SELECT p.id AS id, p.merchant_id AS merchant_id, m.name AS merchant_name, p.amount_paise AS amount_paise, p.due_date AS due_date " +
            "FROM bill_proposal p LEFT JOIN merchant m ON m.id = p.merchant_id WHERE p.status = 'OPEN' ORDER BY p.id",
    )
    fun observeProposals(): Flow<List<BillProposalRow>>

    @Query(
        "SELECT p.id AS id, p.merchant_id AS merchant_id, m.name AS merchant_name, p.amount_paise AS amount_paise, p.due_date AS due_date " +
            "FROM bill_proposal p LEFT JOIN merchant m ON m.id = p.merchant_id WHERE p.id = :id AND p.status = 'OPEN'",
    )
    suspend fun proposal(id: Long): BillProposalRow?

    @Query("DELETE FROM bill_proposal WHERE id = :id")
    suspend fun deleteProposal(id: Long)

    // Bill paid proposals.

    /** The pair is the key, so a pair is proposed at most once, whatever happened to the first proposal. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPaidProposal(row: BillPaidProposalEntity): Long

    @Query("SELECT * FROM bill_paid_proposal WHERE status = 'OPEN' ORDER BY transaction_id, bill_id")
    fun observePaidProposals(): Flow<List<BillPaidProposalEntity>>

    @Query("SELECT * FROM bill_paid_proposal WHERE transaction_id = :txnId AND bill_id = :billId")
    suspend fun paidProposal(txnId: Long, billId: Long): BillPaidProposalEntity?

    @Query("UPDATE bill_paid_proposal SET status = :status WHERE transaction_id = :txnId AND bill_id = :billId")
    suspend fun setPaidProposalStatus(txnId: Long, billId: Long, status: String)

    @Query("DELETE FROM bill_paid_proposal WHERE bill_id = :billId AND status = 'OPEN'")
    suspend fun deleteOpenPaidProposals(billId: Long)

    @Query("DELETE FROM bill_paid_proposal WHERE bill_id = :billId")
    suspend fun deleteAllPaidProposals(billId: Long)
}

/** One metric summed over a period. */
data class MetricTotal(val metric: String, val total: Int)

@Dao
interface QualityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(row: QualityCounterEntity): Long

    @Query("UPDATE quality_counter SET count = count + :delta WHERE origin = :origin AND day = :day AND metric = :metric")
    suspend fun increment(origin: String, day: String, metric: String, delta: Int)

    @Query(
        "SELECT metric AS metric, SUM(count) AS total FROM quality_counter " +
            "WHERE origin = :origin AND day BETWEEN :from AND :to AND metric IN ('PARSED', 'REVIEW', 'CORRECTED') GROUP BY metric",
    )
    fun observeTotals(origin: String, from: String, to: String): Flow<List<MetricTotal>>

    @Query(
        "SELECT metric AS metric, SUM(count) AS total FROM quality_counter " +
            "WHERE origin = :origin AND day BETWEEN :from AND :to AND metric IN ('PARSED', 'REVIEW', 'CORRECTED') GROUP BY metric",
    )
    suspend fun totals(origin: String, from: String, to: String): List<MetricTotal>
}
