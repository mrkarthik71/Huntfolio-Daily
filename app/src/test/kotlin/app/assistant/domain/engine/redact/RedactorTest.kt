package app.assistant.domain.engine.redact

import app.assistant.domain.error.Outcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Duration

/** Task 19.9: the Redactor's behaviour on the Redactor_Test_Set and its fail-closed rules (Requirements 4.3, 46.2, 46.6). */
class RedactorTest {
    private val patterns = RedactorFixtures.patterns()

    private fun redact(text: String): String = (Redactor.redact(text, patterns) as Outcome.Ok).value

    @TestFactory
    fun `every case in the Redactor_Test_Set gives its expected output`(): List<DynamicTest> = RedactorFixtures.cases().map { case ->
        DynamicTest.dynamicTest(checkNotNull(case.name)) {
            assertEquals(case.expected, redact(checkNotNull(case.input)), case.name)
        }
    }

    @Test
    fun `the test set is not empty`() {
        assertTrue(RedactorFixtures.cases().size >= 10)
    }

    @Test
    fun `amounts, dates and times are kept in every form the requirement lists`() {
        val synthetic = RedactorFixtures.synthetic()
        (synthetic.amounts + synthetic.dates + synthetic.times).forEach { kept ->
            assertEquals("paid $kept ok", redact("paid $kept ok"), kept)
        }
    }

    @Test
    fun `a name the test set lists is removed wherever it appears`() {
        RedactorFixtures.synthetic().names.forEach { name ->
            assertEquals("regarding [NAME] thanks", redact("regarding $name thanks"))
            assertEquals("regarding [NAME] thanks", redact("regarding ${name.uppercase()} thanks"), "letter case does not matter")
        }
    }

    @Test
    fun `a name that merely contains a listed name's letters is left alone`() {
        assertEquals("regarding Rahul Vermaa thanks", redact("regarding Rahul Vermaa thanks"))
    }

    @Test
    fun `instruction text from the shipped list never survives, and no placeholder matches an instruction pattern`() {
        val compiled = RedactorFixtures.instructionPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        RedactorFixtures.synthetic().instructions.forEach { instruction ->
            val out = redact("start $instruction. end")
            assertTrue(out.contains(Placeholder.INSTRUCTION.text), "'$instruction' should be replaced, got: $out")
            assertTrue(compiled.none { it.containsMatchIn(out) }, "'$out' still matches an instruction pattern")
        }
        Placeholder.entries.forEach { placeholder ->
            assertTrue(compiled.none { it.containsMatchIn(placeholder.text) }, "${placeholder.text} must not match an instruction pattern")
        }
    }

    @Test
    fun `an input over the length limit is refused, not truncated`() {
        val tooLong = "a".repeat(Redactor.MAX_INPUT_LENGTH + 1)
        assertEquals(Outcome.Err(RedactionError.TOO_LONG), Redactor.redact(tooLong, patterns))
        assertTrue(Redactor.redact("a".repeat(Redactor.MAX_INPUT_LENGTH), patterns) is Outcome.Ok)
    }

    @Test
    fun `a pattern that could loop forever is stopped by the time budget instead of hanging`() {
        val evil = (RedactionPatterns.of(listOf("(.*a){12}b"), timeBudget = Duration.ofMillis(150)) as Outcome.Ok).value
        val started = System.nanoTime()
        val result = Redactor.redact("a".repeat(34) + "!", evil)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(Outcome.Err(RedactionError.TIMED_OUT), result)
        assertTrue(millis < 5_000, "gave up after $millis ms")
    }

    @Test
    fun `a pattern list that would make redaction unsafe is refused`() {
        fun refused(vararg list: String) = RedactionPatterns.of(list.toList()) == Outcome.Err(RedactionError.INVALID_PATTERN)
        assertTrue(refused(""), "blank")
        assertTrue(refused("   "), "blank")
        assertTrue(refused("(unclosed"), "does not compile")
        assertTrue(refused(".*"), "matches the empty text")
        assertTrue(refused("x*"), "matches the empty text")
        assertTrue(refused("name"), "matches the [NAME] placeholder")
        assertTrue(refused("\\[LINK\\]"), "matches the [LINK] placeholder")
        assertTrue(refused("ignore previous", "(bad"), "one bad pattern refuses the whole list")
        assertTrue(RedactionPatterns.of(listOf("ignore previous")) is Outcome.Ok)
        assertTrue(RedactionPatterns.of(emptyList()) is Outcome.Ok, "no patterns is a valid, if weak, configuration")
    }

    @Test
    fun `long adversarial inputs for every detector settle quickly instead of timing out`() {
        val n = 50_000
        val inputs = mapOf(
            "letters" to "a".repeat(n),
            "digits" to "1".repeat(n),
            "digit groups" to "1 ".repeat(n / 2),
            "mask characters" to "X".repeat(n),
            "masks then no digits" to "XXXX ".repeat(n / 5),
            "dotted labels" to "a.".repeat(n / 2),
            "at signs" to "a@".repeat(n / 2),
            "email-like run" to "a.b-c_d+e%f".repeat(n / 11) + "@",
            "scheme only" to "http://".repeat(n / 7),
            "words" to "Dear Mr Anita Rao to From By For ".repeat(n / 33),
            "amount markers" to "Rs ".repeat(n / 3),
            "date-like" to "1-1-1 ".repeat(n / 6),
            "commas" to "1,".repeat(n / 2),
            "instruction starts" to "ignore previous ".repeat(n / 16),
            "sentence-less instruction" to "forward this " + "x ".repeat(n / 2),
        )
        inputs.forEach { (label, text) ->
            val started = System.nanoTime()
            val result = Redactor.redact(text, patterns)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(result is Outcome.Ok, "$label: ${(result as? Outcome.Err)?.error} after $millis ms")
            assertTrue(millis < 1_500, "$label took $millis ms")
        }
    }

    @Test
    fun `redacting a text that is already redacted changes nothing`() {
        val once = redact("Dear Anita Rao, call 9000000001 or mail me@example.com about Rs 500 on 05-01-25")
        assertEquals(once, redact(once))
        assertEquals("Dear [NAME], call [PHONE] or mail [EMAIL] about Rs 500 on 05-01-25", once)
    }
}
