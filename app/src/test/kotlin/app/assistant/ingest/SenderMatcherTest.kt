package app.assistant.ingest

import app.assistant.data.money.BankSenderJson
import app.assistant.domain.model.SenderEntry
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Task 19.1: the Bank_Sender_List match (Requirements 26.1, 26.3, 26.4). */
class SenderMatcherTest {
    private val hdfc = SenderEntry("HDFCBK", prefixPattern = "??-", suffixPattern = "-?")
    private val bare = SenderEntry("PLAINBK")
    private val list = listOf(hdfc, bare)

    private fun matches(sender: String, entries: List<SenderEntry> = list) = SenderMatcher.matches(sender, entries)

    @Test
    fun `DLT-style headers match the core header with the stored prefix and suffix`() {
        listOf("AX-HDFCBK", "VM-HDFCBK-S", "AD-HDFCBK-T", "HDFCBK", "HDFCBK-S", "AX-HDFCBK-G").forEach {
            assertTrue(matches(it), it)
        }
    }

    @Test
    fun `letter case and surrounding space are ignored`() {
        listOf("ax-hdfcbk", "Vm-HdfcBk-s", "  AX-HDFCBK  ", "hdfcbk", "plainbk", "PlainBK").forEach { assertTrue(matches(it), it) }
    }

    @Test
    fun `look-alike senders are not matched`() {
        listOf(
            "FAKEHDFCBK", // text before the core without the stored separator
            "HDFCBK2", // text after the core
            "HDFCBKS", // suffix without its hyphen
            "XX-HDFCBK-SS", // suffix longer than the pattern
            "XXX-HDFCBK", // prefix longer than the pattern
            "AX-HDFCBK-S-S", // doubled suffix
            "HDFC-BK", // core split
            "AX HDFCBK", // space instead of the hyphen
            "AX-HDFCBK\u200B", // zero-width character after
            "A-HDFCBK", // prefix shorter than the pattern
            "AX-PLAINBK", // an entry with no prefix pattern accepts none
            "PLAINBK-S", // ... nor a suffix
            "",
            "   ",
        ).forEach { assertFalse(matches(it), "should reject '$it'") }
    }

    @Test
    fun `an empty list matches nothing, and entry order does not matter`() {
        assertFalse(matches("AX-HDFCBK", emptyList()))
        assertEquals(matches("VM-HDFCBK-S", list), matches("VM-HDFCBK-S", list.reversed()))
    }

    @Test
    fun `a very long sender is not a bank and is never matched`() {
        assertFalse(matches("AX-HDFCBK" + "-S".repeat(500)))
        assertFalse(matches("A".repeat(10_000)))
    }

    @Test
    fun `a question mark stands for one letter or digit and nothing else in a pattern is special`() {
        val special = SenderEntry("CORE", prefixPattern = "[x]*.")
        assertTrue(matches("[x]*.CORE", listOf(special)), "regex characters are literal")
        assertFalse(matches("xxxCORE", listOf(special)))
        assertFalse(matches("a-CORE", listOf(SenderEntry("CORE", prefixPattern = "?"))), "a hyphen is not a letter or digit")
        assertTrue(matches("7CORE", listOf(SenderEntry("CORE", prefixPattern = "?"))))
    }

    @Test
    fun `an entry must have a usable core header`() {
        assertThrows(IllegalArgumentException::class.java) { SenderEntry("") }
        assertThrows(IllegalArgumentException::class.java) { SenderEntry(" HDFCBK") }
        assertThrows(IllegalArgumentException::class.java) { SenderEntry("HDF?BK") }
    }

    private data class SenderCase(val core: String, val operator: String, val suffix: String, val upper: Boolean)

    private val arbCase: Arb<SenderCase> = arbitrary {
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val coreLength = Arb.int(3..8).bind()
        SenderCase(
            core = (1..coreLength).map { letters[Arb.int(letters.indices).bind()] }.joinToString(""),
            operator = (1..2).map { letters[Arb.int(letters.indices).bind()] }.joinToString(""),
            suffix = letters[Arb.int(letters.indices).bind()].toString(),
            upper = Arb.element(true, false).bind(),
        )
    }

    @Test
    fun `any operator prefix, suffix letter and letter case of a stored core header matches, and nothing extra does`() = runTest {
        checkAll(arbCase) { case ->
            val entry = SenderEntry(case.core, "??-", "-?")
            val sender = "${case.operator}-${case.core}-${case.suffix}".let { if (case.upper) it else it.lowercase() }
            assertTrue(SenderMatcher.matches(sender, listOf(entry)), sender)
            assertFalse(SenderMatcher.matches("Z$sender", listOf(entry)), "a character before the prefix")
            assertFalse(SenderMatcher.matches("${sender}Z", listOf(entry)), "a character after the suffix")
            assertFalse(SenderMatcher.matches(sender.replace(case.core, case.core + "X", ignoreCase = true), listOf(entry)), "a changed core")
        }
    }

    @Test
    fun `the shipped sender list loads and recognises the placeholder banks used by the fixtures`() {
        val entries = BankSenderJson.parse(File("src/main/assets/bank_senders.json").readText()).getOrThrow()
        assertEquals(setOf("BANKA", "BANKB"), entries.map { it.coreHeader }.toSet())
        assertTrue(SenderMatcher.matches("AD-BANKA-S", entries))
        assertTrue(SenderMatcher.matches("VM-BANKB-T", entries))
        assertFalse(SenderMatcher.matches("XX-OTHER-S", entries), "an unlisted sender is discarded (Requirement 26.3)")
    }

    @Test
    fun `a malformed sender list is rejected instead of silently dropping a bank`() {
        assertTrue(BankSenderJson.parse("""{"senders":[{"prefix_pattern":"??-"}]}""").isFailure)
        assertTrue(BankSenderJson.parse("""{"senders":[{"core_header":"A?B"}]}""").isFailure)
        assertTrue(BankSenderJson.parse("not json").isFailure)
        assertEquals(emptyList<SenderEntry>(), BankSenderJson.parse("""{"senders":[]}""").getOrThrow())
    }
}
