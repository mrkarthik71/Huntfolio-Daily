package app.assistant.data.money

import app.assistant.data.db.AppDatabase
import app.assistant.data.db.dao.SeedMerchant
import app.assistant.data.db.entity.SenderEntryEntity
import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.model.BuiltinCategories

/**
 * Puts the shipped categories, default Merchants and Bank_Sender_List into a new Phase 3 store
 * (Requirements 26.4, 31.5). The lists are app assets read through [readAsset]; they are static
 * app data, not user data. A bad asset throws with a message naming it, which the build's tests
 * catch, so a shipped app never fails here.
 */
class MoneySeeder(private val readAsset: (String) -> String) {

    /** Seeds [db] if it has no built-in category yet. Returns true when it wrote anything. */
    suspend fun seed(db: AppDatabase): Boolean {
        val merchants = DefaultMerchantsJson.parse(readAsset(MERCHANTS_ASSET)).getOrElse { throw IllegalStateException("$MERCHANTS_ASSET: ${it.message}", it) }
        val senders = BankSenderJson.parse(readAsset(SENDERS_ASSET)).getOrElse { throw IllegalStateException("$SENDERS_ASSET: ${it.message}", it) }

        val seedMerchants = merchants.map { m ->
            val category = checkNotNull(BuiltinCategories.idOf(m.categoryName)) { "$MERCHANTS_ASSET: ${m.name} has unknown category ${m.categoryName}" }
            SeedMerchant(m.name, Categoriser.normalise(m.name), category.value, m.aliases.map(Categoriser::normalise).filter { it.isNotEmpty() }.distinct())
        }
        val seedSenders = senders.map {
            SenderEntryEntity(core = it.coreHeader, prefixPattern = it.prefixPattern, suffixPattern = it.suffixPattern, builtin = true, enabled = true)
        }
        return db.moneySeedDao().seedIfEmpty(BuiltinCategories.NAMES, seedMerchants, seedSenders)
    }

    companion object {
        const val MERCHANTS_ASSET = "merchants.json"
        const val SENDERS_ASSET = "bank_senders.json"
    }
}
