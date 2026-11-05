package app.assistant.data.money

import app.assistant.domain.model.DefaultMerchant
import com.google.gson.Gson

/**
 * Reads the shipped default Merchant list (`merchants.json` in the app assets; Requirement 31.5:
 * data kept apart from the Categoriser code). Gson ignores Kotlin default values, so every wire
 * field is nullable and checked here, not in the domain type.
 */
object DefaultMerchantsJson {
    private val gson = Gson()

    private data class FileDto(val merchants: List<EntryDto>?)

    private data class EntryDto(val name: String?, val aliases: List<String>?, val category: String?)

    /** A malformed entry fails the whole list instead of silently shipping a merchant with no category. */
    fun parse(json: String): Result<List<DefaultMerchant>> = runCatching {
        val dto = gson.fromJson(json, FileDto::class.java) ?: throw IllegalArgumentException("empty merchant list")
        dto.merchants.orEmpty().mapIndexed { index, e ->
            val name = requireNotNull(e.name?.trim()?.ifEmpty { null }) { "merchant #$index: needs a non-empty name" }
            val category = requireNotNull(e.category?.trim()?.ifEmpty { null }) { "merchant #$index ($name): needs a category" }
            DefaultMerchant(name, e.aliases.orEmpty(), category)
        }
    }
}
