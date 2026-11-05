package app.assistant.data.repo

import app.assistant.data.db.dao.MerchantCatalogDao
import app.assistant.data.db.entity.MerchantAliasEntity
import app.assistant.data.db.entity.MerchantEntity
import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Merchant
import app.assistant.domain.model.MerchantId

/** Finds or creates the Merchant for a name or VPA, and picks a category for it (Requirements 31.1, 31.2, 31.7). */
internal class MerchantResolver(private val catalog: MerchantCatalogDao) {

    /** The Merchant matching [nameOrVpa], or a new one with that text as name and alias. Null for blank text. */
    suspend fun resolve(nameOrVpa: String?): Long? {
        val text = nameOrVpa?.trim().orEmpty()
        if (text.isEmpty()) return null
        val norm = Categoriser.normalise(text)
        catalog.idByName(norm)?.let { return it }
        catalog.idByAlias(norm)?.let { return it }
        val created = checkNotNull(Categoriser.newMerchant(text)) { "Non-blank text always makes a Merchant" }
        val id = catalog.insertMerchant(MerchantEntity(name = created.name, nameNorm = norm, defaultCategoryId = null, builtin = false))
        created.aliases.forEach { catalog.insertAlias(MerchantAliasEntity(merchantId = id, aliasNorm = Categoriser.normalise(it))) }
        return id
    }

    /** The User's latest correction for the Merchant, else its default category, else Uncategorised. */
    suspend fun categoryFor(merchantId: Long?): Long {
        if (merchantId == null) return CategoryId.UNCATEGORISED.value
        // Only the category fields matter to the precedence rule, so the name and aliases are left out.
        val merchant =
            Merchant(MerchantId(merchantId), name = "", aliases = emptyList(), defaultCategory = catalog.defaultCategory(merchantId)?.let(::CategoryId))
        return Categoriser.categoryFor(merchant, catalog.latestCorrection(merchantId)?.let(::CategoryId)).value
    }
}
