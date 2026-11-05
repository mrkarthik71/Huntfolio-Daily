package app.assistant.parse.money

import app.assistant.domain.model.Paise
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The anonymised Money_Message fixtures under `src/test/resources/fixtures/money` (design:
 * fixture-driven tests). A file at `<bank>/<format-id>/NNN.json` holds a message, its origin and
 * Received_Time, and the expected result; `synthetic_values.json` lists the only names, VPAs,
 * references and account digits a fixture may contain.
 */
object MoneyFixtures {
    val root = File("src/test/resources/fixtures/money")

    private val gson = Gson()

    data class Expected(
        val result: String?,
        @SerializedName("format_id") val formatId: String?,
        val type: String?,
        val reversal: Boolean?,
        @SerializedName("amount_paise") val amountPaise: Long?,
        val at: String?,
        @SerializedName("merchant_or_vpa") val merchantOrVpa: String?,
        val account4: String?,
        val reference: String?,
        @SerializedName("balance_paise") val balancePaise: Long?,
        val reason: String?,
    )

    private data class FixtureDto(
        val origin: String?,
        val source: String?,
        @SerializedName("received_at") val receivedAt: String?,
        val zone: String?,
        val injection: Boolean?,
        val text: String?,
        val expected: Expected?,
    )

    private data class SyntheticDto(
        val merchants: List<String>?,
        val names: List<String>?,
        val vpas: List<String>?,
        val references: List<String>?,
        val account4: List<String>?,
        val phones: List<String>?,
        @SerializedName("injection_hosts") val injectionHosts: List<String>?,
    )

    class Synthetic(
        val merchants: Set<String>,
        val names: Set<String>,
        val vpas: Set<String>,
        val references: Set<String>,
        val account4: Set<String>,
        val phones: Set<String>,
        val injectionHosts: Set<String>,
    )

    class Fixture(
        val file: File,
        /** `<bank>` and `<format-id>`: the two directories above the file. */
        val bank: String,
        val group: String,
        val origin: String,
        val source: String,
        val receivedAt: LocalDateTime,
        val zone: ZoneId,
        val injection: Boolean,
        val text: String,
        private val expected: Expected,
    ) {
        val id: String get() = "$bank/$group/${file.nameWithoutExtension}"

        val result: String get() = expected.result.orEmpty()
        val formatId: String? get() = expected.formatId
        val reversal: Boolean get() = expected.reversal == true
        val amountPaise: Long? get() = expected.amountPaise
        val merchantOrVpa: String? get() = expected.merchantOrVpa
        val account4: String? get() = expected.account4
        val reference: String? get() = expected.reference
        val reason: String? get() = expected.reason

        /** What the Message_Parser must return for this fixture. */
        fun expectedResult(): ParseResult = when (result) {
            "PARSED" -> ParseResult.Parsed(
                ParsedMessage(
                    type = MessageType.valueOf(checkNotNull(expected.type)),
                    isReversal = reversal,
                    amount = Paise(checkNotNull(expected.amountPaise)),
                    at = LocalDateTime.parse(checkNotNull(expected.at)),
                    merchantOrVpa = expected.merchantOrVpa,
                    account4 = expected.account4,
                    reference = expected.reference,
                    balance = expected.balancePaise?.let(::Paise),
                ),
                checkNotNull(expected.formatId),
            )
            "OTP" -> ParseResult.Otp
            "FAILED" -> ParseResult.FailedPayment(checkNotNull(expected.formatId))
            "UNKNOWN" -> ParseResult.Unknown(UnknownReason.valueOf(checkNotNull(expected.reason)))
            else -> error("${file.path}: unknown expected result '$result'")
        }
    }

    fun all(): List<Fixture> = root.walkTopDown().filter {
        it.isFile && it.extension == "json" && it.name != SYNTHETIC_FILE
    }.sortedBy { it.path }.map(::load).toList()

    private fun load(file: File): Fixture {
        val dto = gson.fromJson(file.readText(Charsets.UTF_8), FixtureDto::class.java)
        return Fixture(
            file = file,
            bank = file.parentFile.parentFile.name,
            group = file.parentFile.name,
            origin = checkNotNull(dto.origin) { "${file.path}: origin" },
            source = checkNotNull(dto.source) { "${file.path}: source" },
            receivedAt = LocalDateTime.parse(checkNotNull(dto.receivedAt) { "${file.path}: received_at" }),
            zone = ZoneId.of(checkNotNull(dto.zone) { "${file.path}: zone" }),
            injection = dto.injection == true,
            text = checkNotNull(dto.text) { "${file.path}: text" },
            expected = checkNotNull(dto.expected) { "${file.path}: expected" },
        )
    }

    fun synthetic(): Synthetic {
        val dto = gson.fromJson(File(root, SYNTHETIC_FILE).readText(Charsets.UTF_8), SyntheticDto::class.java)
        return Synthetic(
            merchants = dto.merchants.orEmpty().toSet(),
            names = dto.names.orEmpty().toSet(),
            vpas = dto.vpas.orEmpty().toSet(),
            references = dto.references.orEmpty().toSet(),
            account4 = dto.account4.orEmpty().toSet(),
            phones = dto.phones.orEmpty().toSet(),
            injectionHosts = dto.injectionHosts.orEmpty().toSet(),
        )
    }

    private const val SYNTHETIC_FILE = "synthetic_values.json"
}
