package app.assistant.domain.engine.redact

import app.assistant.data.money.InstructionPatternsJson
import app.assistant.domain.error.Outcome
import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets

/** The Redactor_Test_Set (design: the fixtures/redactor JSON files) and the shipped patterns, loaded for the tests. */
object RedactorFixtures {
    private val dir = File("src/test/resources/fixtures/redactor")
    private val gson = Gson()

    data class Case(val name: String?, val input: String?, val expected: String?)

    private data class CasesDto(val cases: List<Case>?)

    data class Synthetic(
        val names: List<String>,
        val contextNames: List<String>,
        val phones: List<String>,
        val maskedAccounts: List<String>,
        val cards: List<String>,
        val digitAccounts: List<String>,
        val upiIds: List<String>,
        val emails: List<String>,
        val links: List<String>,
        val instructions: List<String>,
        val amounts: List<String>,
        val dates: List<String>,
        val times: List<String>,
    )

    private data class SyntheticDto(
        val names: List<String>?,
        @com.google.gson.annotations.SerializedName("context_names") val contextNames: List<String>?,
        val phones: List<String>?,
        @com.google.gson.annotations.SerializedName("masked_accounts") val maskedAccounts: List<String>?,
        val cards: List<String>?,
        @com.google.gson.annotations.SerializedName("digit_accounts") val digitAccounts: List<String>?,
        @com.google.gson.annotations.SerializedName("upi_ids") val upiIds: List<String>?,
        val emails: List<String>?,
        val links: List<String>?,
        val instructions: List<String>?,
        val amounts: List<String>?,
        val dates: List<String>?,
        val times: List<String>?,
    )

    val instructionPatterns: List<String> =
        InstructionPatternsJson.parse(File("src/main/assets/instruction_patterns.json").readText(StandardCharsets.UTF_8)).getOrThrow()

    fun cases(): List<Case> = gson.fromJson(File(dir, "cases.json").readText(StandardCharsets.UTF_8), CasesDto::class.java).cases.orEmpty()

    fun synthetic(): Synthetic {
        val d = gson.fromJson(File(dir, "synthetic_pii.json").readText(StandardCharsets.UTF_8), SyntheticDto::class.java)
        return Synthetic(
            names = d.names.orEmpty(),
            contextNames = d.contextNames.orEmpty(),
            phones = d.phones.orEmpty(),
            maskedAccounts = d.maskedAccounts.orEmpty(),
            cards = d.cards.orEmpty(),
            digitAccounts = d.digitAccounts.orEmpty(),
            upiIds = d.upiIds.orEmpty(),
            emails = d.emails.orEmpty(),
            links = d.links.orEmpty(),
            instructions = d.instructions.orEmpty(),
            amounts = d.amounts.orEmpty(),
            dates = d.dates.orEmpty(),
            times = d.times.orEmpty(),
        )
    }

    /** The shipped Instruction_Pattern_List plus the test set's person names, as production would configure it. */
    fun patterns(): RedactionPatterns = when (val result = RedactionPatterns.of(instructionPatterns, synthetic().names)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> error("the shipped redaction patterns were refused: ${result.error}")
    }
}
