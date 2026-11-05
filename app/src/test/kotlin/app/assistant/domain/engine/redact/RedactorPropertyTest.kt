// Feature: Phase 3 Money — Property 61: Redactor removes every protected pattern; Property 62: Redactor is idempotent.
package app.assistant.domain.engine.redact

import app.assistant.domain.error.Outcome
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A piece of generated text and what, if anything, it is expected to turn into. */
private data class Piece(val text: String, val placeholder: Placeholder?, val kept: Boolean)

private val filler = listOf("please", "check", "status", "payment", "balance", "thanks", "regarding", "message", "office", "team", "okay", "note")

/** Independent detectors for the output check: written from the requirement, not copied from the Redactor. */
private val emailLike = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
private val upiLike = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9]+")
private val linkLike = Regex("(?i)(https?://|www\\.)")
private val phoneLike = Regex("(?<!\\d)[6-9]\\d{9}(?!\\d)")
private val longDigits = Regex("\\d{9,}")
private val maskedLike = Regex("[Xx*]{2,}\\s?\\d{2,}")

class RedactorPropertyTest {
    private val patterns = RedactorFixtures.patterns()
    private val synthetic = RedactorFixtures.synthetic()
    private val instruction = RedactorFixtures.instructionPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }

    private val pii: List<Piece> =
        synthetic.names.map { Piece(it, Placeholder.NAME, false) } +
            synthetic.phones.map { Piece(it, Placeholder.PHONE, false) } +
            synthetic.maskedAccounts.map { Piece(it, Placeholder.ACCOUNT, false) } +
            synthetic.digitAccounts.map { Piece(it, Placeholder.ACCOUNT, false) } +
            synthetic.cards.map { Piece(it, Placeholder.CARD, false) } +
            synthetic.upiIds.map { Piece(it, Placeholder.UPI, false) } +
            synthetic.emails.map { Piece(it, Placeholder.EMAIL, false) } +
            synthetic.links.map { Piece(it, Placeholder.LINK, false) } +
            // A sentence-ending full stop follows an instruction, because instruction patterns end at one.
            synthetic.instructions.map { Piece("$it.", Placeholder.INSTRUCTION, false) } +
            synthetic.contextNames.flatMap { listOf(Piece("Dear $it,", Placeholder.NAME, false), Piece("paid to $it", Placeholder.NAME, false)) }

    private val kept: List<Piece> = (synthetic.amounts + synthetic.dates + synthetic.times).map { Piece(it, null, true) }

    private val arbText = arbitrary {
        val count = Arb.int(1..10).bind()
        val pieces = ArrayList<Piece>()
        repeat(count) {
            val roll = Arb.int(0..9).bind()
            pieces += when {
                roll < 4 -> Piece(Arb.element(filler).bind(), null, false)
                roll < 7 -> Arb.element(pii).bind()
                else -> Arb.element(kept).bind()
            }
        }
        pieces.toList()
    }

    private fun redact(text: String): String = (Redactor.redact(text, patterns) as Outcome.Ok).value

    @Test
    fun `Property 61 - no protected item survives, each is replaced by its type, and amounts, dates and times stay`() = runTest {
        checkAll(arbText) { pieces ->
            // A lowercase word between pieces keeps two capitalised names from running together into one name.
            val input = pieces.joinToString(" okay ") { it.text }
            val output = redact(input)

            pieces.filter { it.placeholder != null }.forEach { piece ->
                // A name that follows "paid to" / "Dear" is the part removed; the keyword stays.
                val removed = piece.text.removePrefix("Dear ").removePrefix("paid to ").trimEnd(',', '.')
                assertFalse(output.contains(removed), "'$removed' survived in: $output")
                assertTrue(output.contains(checkNotNull(piece.placeholder).text), "no ${piece.placeholder} placeholder for '${piece.text}' in: $output")
            }
            pieces.filter { it.kept }.forEach { assertTrue(output.contains(it.text), "'${it.text}' was removed from: $output") }

            // Kept amounts may legitimately have many digits (e.g. "Rs 99999999999"), so look for identifiers outside them.
            val outsideKept = pieces.filter { it.kept }.fold(output) { acc, piece -> acc.replace(piece.text, " ") }
            assertFalse(emailLike.containsMatchIn(output), "email pattern in: $output")
            assertFalse(upiLike.containsMatchIn(output), "UPI pattern in: $output")
            assertFalse(linkLike.containsMatchIn(output), "link in: $output")
            assertFalse(phoneLike.containsMatchIn(outsideKept), "phone number in: $output")
            assertFalse(longDigits.containsMatchIn(outsideKept), "account or card number in: $output")
            assertFalse(maskedLike.containsMatchIn(output), "masked number in: $output")
            assertTrue(instruction.none { it.containsMatchIn(output) }, "instruction text in: $output")
        }
    }

    private val junk = "abcXYZ 0123456789 -/.:,@+*\u20B9\n"

    @Test
    fun `Property 62 - redacting twice gives the same output as redacting once, for any text`() = runTest {
        val arbAny = arbitrary {
            val parts = ArrayList<String>()
            repeat(Arb.int(0..12).bind()) {
                parts += when (Arb.int(0..3).bind()) {
                    0 -> Arb.element(pii).bind().text
                    1 -> Arb.element(kept).bind().text
                    else -> (1..Arb.int(0..12).bind()).map { junk[Arb.int(junk.indices).bind()] }.joinToString("")
                }
            }
            parts.joinToString(if (Arb.int(0..1).bind() == 0) " " else "")
        }
        checkAll(arbAny) { text ->
            val first = Redactor.redact(text, patterns)
            assertTrue(first is Outcome.Ok, "redaction should settle for: $text")
            val once = (first as Outcome.Ok).value
            assertEquals(Outcome.Ok(once), Redactor.redact(once, patterns), "not idempotent for: $text")
        }
    }
}
