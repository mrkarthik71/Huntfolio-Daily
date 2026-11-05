package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.assistant.data.db.entity.CategoryEntity
import app.assistant.data.db.entity.MerchantAliasEntity
import app.assistant.data.db.entity.MerchantEntity
import app.assistant.data.db.entity.SenderEntryEntity

/** A shipped Merchant ready to store: names already normalised, category already resolved to its id. */
data class SeedMerchant(val name: String, val nameNorm: String, val categoryId: Long, val aliasNorms: List<String>)

/** Writes the shipped categories, Merchants and senders into an empty Phase 3 store. */
@Dao
interface MoneySeedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(merchant: MerchantEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAliasEntity): Long

    @Insert
    suspend fun insertSender(sender: SenderEntryEntity): Long

    @Query("SELECT COUNT(*) FROM category WHERE builtin = 1")
    suspend fun builtinCategoryCount(): Int

    @Query("SELECT id FROM merchant WHERE name_norm = :nameNorm")
    suspend fun merchantIdByNorm(nameNorm: String): Long?

    /**
     * Seeds everything in one transaction, and only while no built-in category exists. That makes
     * the first category row the marker: a half-finished seed rolls back, and a User who later
     * deletes a shipped Merchant does not get it back at the next start. Returns whether it seeded.
     */
    @Transaction
    suspend fun seedIfEmpty(categoryNames: List<String>, merchants: List<SeedMerchant>, senders: List<SenderEntryEntity>): Boolean {
        if (builtinCategoryCount() > 0) return false
        categoryNames.forEachIndexed { index, name -> insertCategory(CategoryEntity(id = index + 1L, name = name, builtin = true)) }
        for (m in merchants) {
            val inserted = insertMerchant(MerchantEntity(name = m.name, nameNorm = m.nameNorm, defaultCategoryId = m.categoryId, builtin = true))
            val id = if (inserted > 0) inserted else checkNotNull(merchantIdByNorm(m.nameNorm))
            m.aliasNorms.forEach { insertAlias(MerchantAliasEntity(merchantId = id, aliasNorm = it)) }
        }
        senders.forEach { insertSender(it) }
        return true
    }
}
