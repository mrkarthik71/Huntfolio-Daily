package app.assistant.data.money

import app.assistant.domain.model.SenderEntry
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Reads the shipped Bank_Sender_List (`bank_senders.json` in the app assets; Requirement 26.4:
 * kept as data apart from parser code, so a sender can be added or removed without changing code).
 * User additions are stored in Room, not here. Gson ignores Kotlin default values, so every wire
 * field is nullable and checked.
 */
object BankSenderJson {
    private val gson = Gson()

    private data class FileDto(val senders: List<EntryDto>?)

    private data class EntryDto(
        @SerializedName("core_header") val coreHeader: String?,
        @SerializedName("prefix_pattern") val prefixPattern: String?,
        @SerializedName("suffix_pattern") val suffixPattern: String?,
    )

    /** A malformed entry fails the whole list instead of silently dropping a bank the User relies on. */
    fun parse(json: String): Result<List<SenderEntry>> = runCatching {
        val dto = gson.fromJson(json, FileDto::class.java) ?: throw IllegalArgumentException("empty sender list")
        dto.senders.orEmpty().mapIndexed { index, e ->
            SenderEntry(
                coreHeader = requireNotNull(e.coreHeader) { "sender #$index: needs a core_header" },
                prefixPattern = e.prefixPattern,
                suffixPattern = e.suffixPattern,
            )
        }
    }
}
