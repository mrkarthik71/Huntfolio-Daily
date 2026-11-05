package app.assistant.data.money

import app.assistant.parse.money.BankFormatFactory
import app.assistant.parse.money.RegistryFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Reads one parser-registry JSON file (design: Bank_Formats are JSON, compiled at startup into
 * matchers). The wire shape lives only here; [app.assistant.parse.money.BankFormat] carries no
 * serialization annotations, and `parse` stays free of JSON libraries. Gson ignores Kotlin default
 * values, so every wire field is nullable and defaulted by [BankFormatFactory].
 */
object ParserRegistryJson {
    private val gson = Gson()

    private data class FileDto(@SerializedName("otp_keywords") val otpKeywords: List<String>?, val formats: List<FormatDto>?)

    private data class FormatDto(
        val id: String?,
        val origins: List<String>?,
        val kind: String?,
        val template: String?,
        @SerializedName("date_order") val dateOrder: String?,
        @SerializedName("date_separators") val dateSeparators: String?,
    )

    /**
     * A malformed entry fails the whole file with its id or index, rather than silently dropping a
     * format (a format the User relies on must not vanish).
     */
    fun parse(json: String): Result<RegistryFile> = runCatching {
        val dto = gson.fromJson(json, FileDto::class.java) ?: throw IllegalArgumentException("empty registry file")
        val formats = dto.formats.orEmpty().mapIndexed { index, f ->
            BankFormatFactory.create(f.id, f.origins, f.kind, f.template, f.dateOrder, f.dateSeparators, index)
        }
        RegistryFile(dto.otpKeywords.orEmpty(), formats)
    }
}
