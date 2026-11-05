package app.assistant.data.money

import com.google.gson.Gson

/**
 * Reads the shipped Instruction_Pattern_List (`instruction_patterns.json` in the app assets;
 * Requirement 4.3: a configurable list kept as data). The strings are regular expressions; the
 * Redactor's `RedactionPatterns.of` is what validates them, so a list that could make redaction
 * unsafe is refused there and nothing is redacted with it.
 */
object InstructionPatternsJson {
    private val gson = Gson()

    private data class FileDto(val patterns: List<String>?)

    fun parse(json: String): Result<List<String>> = runCatching {
        val dto = gson.fromJson(json, FileDto::class.java) ?: throw IllegalArgumentException("empty pattern list")
        dto.patterns.orEmpty().also { list -> require(list.none { it.isBlank() }) { "a pattern must not be blank" } }
    }
}
