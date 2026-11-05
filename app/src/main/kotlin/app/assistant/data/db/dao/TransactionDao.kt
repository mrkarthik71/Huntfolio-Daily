package app.assistant.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.assistant.data.db.entity.CategoryCorrectionEntity
import app.assistant.data.db.entity.ForcedMergeEntity
import app.assistant.data.db.entity.MerchantAliasEntity
import app.assistant.data.db.entity.MerchantEntity
import app.assistant.data.db.entity.ParsedFactEntity
import app.assistant.data.db.entity.TombstoneEntity
import app.assistant.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

/** A stored Transaction with its Merchant's name, for lists. */
data class TransactionRow(@Embedded val txn: TransactionEntity, @ColumnInfo(name = "merchant_name") val merchantName: String?)

/** Which Transaction a stored message belongs to. */
data class FactLink(@ColumnInfo(name = "source_ref") val sourceRef: String, @ColumnInfo(name = "transaction_id") val transactionId: Long)

@Dao
interface TransactionDao {
    // Parsed facts

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFact(fact: ParsedFactEntity): Long

    @Query("SELECT * FROM parsed_fact WHERE source_ref IN (:refs)")
    suspend fun factsByRefs(refs: List<String>): List<ParsedFactEntity>

    @Query("SELECT * FROM parsed_fact WHERE type = :type AND amount_paise = :amount AND at BETWEEN :from AND :to")
    suspend fun factsNear(type: String, amount: Long, from: Long, to: Long): List<ParsedFactEntity>

    @Query("SELECT * FROM parsed_fact WHERE transaction_id IN (:transactionIds)")
    suspend fun factsOfTransactions(transactionIds: List<Long>): List<ParsedFactEntity>

    @Query("SELECT f.* FROM parsed_fact f JOIN forced_merge m ON m.source_ref = f.source_ref WHERE m.group_id IN (:groups)")
    suspend fun factsInGroups(groups: List<Long>): List<ParsedFactEntity>

    @Query("SELECT source_ref FROM parsed_fact WHERE transaction_id = :transactionId")
    suspend fun refsOf(transactionId: Long): List<String>

    @Query("UPDATE parsed_fact SET transaction_id = :transactionId WHERE source_ref IN (:refs)")
    suspend fun linkFacts(refs: List<String>, transactionId: Long)

    @Query("DELETE FROM parsed_fact WHERE source_ref IN (:refs)")
    suspend fun deleteFacts(refs: List<String>)

    // Forced merges

    @Query("SELECT DISTINCT group_id FROM forced_merge WHERE source_ref IN (:refs)")
    suspend fun groupsOf(refs: List<String>): List<Long>

    @Query("SELECT * FROM forced_merge WHERE group_id IN (:groups)")
    suspend fun forcedInGroups(groups: List<Long>): List<ForcedMergeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putForced(rows: List<ForcedMergeEntity>)

    @Query("UPDATE forced_merge SET group_id = :to WHERE group_id IN (:from)")
    suspend fun relabelGroups(from: List<Long>, to: Long)

    @Query("DELETE FROM forced_merge WHERE source_ref IN (:refs)")
    suspend fun deleteForced(refs: List<String>)

    // Tombstones

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstones(rows: List<TombstoneEntity>)

    @Query("SELECT COUNT(*) FROM tombstone WHERE source_ref = :ref")
    suspend fun tombstoneCount(ref: String): Int

    // Transactions

    @Insert
    suspend fun insertTxn(txn: TransactionEntity): Long

    @Update
    suspend fun updateTxn(txn: TransactionEntity)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun txn(id: Long): TransactionEntity?

    @Query("SELECT * FROM txn WHERE id IN (:ids)")
    suspend fun txns(ids: List<Long>): List<TransactionEntity>

    @Query("DELETE FROM txn WHERE id IN (:ids)")
    suspend fun deleteTxns(ids: List<Long>)

    @Query("SELECT t.*, m.name AS merchant_name FROM txn t LEFT JOIN merchant m ON m.id = t.merchant_id WHERE t.id = :id")
    suspend fun row(id: Long): TransactionRow?

    @Query("SELECT t.*, m.name AS merchant_name FROM txn t LEFT JOIN merchant m ON m.id = t.merchant_id ORDER BY t.at DESC, t.id DESC")
    fun observeRows(): Flow<List<TransactionRow>>

    @Query("SELECT source_ref, transaction_id FROM parsed_fact WHERE transaction_id IS NOT NULL")
    fun observeLinks(): Flow<List<FactLink>>

    @Query("SELECT source_ref, transaction_id FROM parsed_fact WHERE transaction_id = :transactionId")
    suspend fun linksOf(transactionId: Long): List<FactLink>

    // Category corrections (Requirement 31.6): Transactions of the Merchant before the anchor, not set individually.

    @Query(
        "SELECT COUNT(*) FROM txn WHERE merchant_id = :merchant AND category_user_set = 0 AND category_id != :category " +
            "AND (at < :anchorAt OR (at = :anchorAt AND id < :anchorId))",
    )
    suspend fun countEarlierToChange(merchant: Long, category: Long, anchorAt: Long, anchorId: Long): Int

    @Query(
        "UPDATE txn SET category_id = :category WHERE merchant_id = :merchant AND category_user_set = 0 AND category_id != :category " +
            "AND (at < :anchorAt OR (at = :anchorAt AND id < :anchorId))",
    )
    suspend fun applyCategoryToEarlier(merchant: Long, category: Long, anchorAt: Long, anchorId: Long): Int

    /** Debit Transactions with a Merchant, for subscription detection (Requirement 37.1). Reversals are excluded. */
    @Query(
        "SELECT id, merchant_id AS merchantId, amount_paise AS amountPaise, at FROM txn WHERE type = 'DEBIT' " +
            "AND is_reversal = 0 AND merchant_id IS NOT NULL ORDER BY at ASC",
    )
    suspend fun debitChargesForSubscriptions(): List<DebitChargeRow>
}

/** A debit Transaction projected for the Subscription_Detector: id, Merchant, amount and time. */
data class DebitChargeRow(val id: Long, val merchantId: Long?, val amountPaise: Long, val at: Long)

/** Merchant, alias, category and correction lookups used when a Transaction is stored (Requirements 31.1, 31.2, 31.7). */
@Dao
interface MerchantCatalogDao {
    @Query("SELECT id FROM merchant WHERE name_norm = :norm")
    suspend fun idByName(norm: String): Long?

    @Query("SELECT merchant_id FROM merchant_alias WHERE alias_norm = :norm")
    suspend fun idByAlias(norm: String): Long?

    @Query("SELECT name FROM merchant WHERE id = :id")
    suspend fun name(id: Long): String?

    @Query("SELECT default_category_id FROM merchant WHERE id = :id")
    suspend fun defaultCategory(id: Long): Long?

    @Query("SELECT category_id FROM category_correction WHERE merchant_id = :id ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun latestCorrection(id: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(merchant: MerchantEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAliasEntity): Long

    @Insert
    suspend fun insertCorrection(correction: CategoryCorrectionEntity): Long

    @Query("SELECT COUNT(*) FROM category WHERE id = :id")
    suspend fun categoryCount(id: Long): Int
}
