package app.assistant.domain.engine.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchRulesTest {
    private val items = listOf(
        Searchable(SearchKind.NOTE, "Car insurance", "Policy renews in March; agent Suresh", LocalDate.of(2026, 1, 5)),
        Searchable(SearchKind.PAYMENT, "Café Coffee Day", "₹240", LocalDate.of(2026, 11, 2)),
        Searchable(SearchKind.TASK, "Renew insurance for the bike", "", LocalDate.of(2026, 11, 10)),
        Searchable(SearchKind.TRIP, "12627 KARNATAKA EXP", "PNR 4521234567", LocalDate.of(2026, 12, 15)),
    )

    @Test
    fun everyWordMustMatchAndTitleStartsRankFirst() {
        val hits = SearchRules.search(items, "insurance")
        // Both have "insurance" as a whole word in the title, so the more recent one comes first.
        assertEquals(listOf("Renew insurance for the bike", "Car insurance"), hits.map { it.item.title })
        // A title that starts with the word beats one that only contains it.
        assertEquals("Car insurance", SearchRules.search(items, "car").first().item.title)
        assertEquals(listOf("Car insurance"), SearchRules.search(items, "insurance march").map { it.item.title })
    }

    @Test
    fun ignoresCaseAndAccentsAndFindsBodyText() {
        assertEquals("Café Coffee Day", SearchRules.search(items, "CAFE").single().item.title)
        assertEquals(SearchKind.TRIP, SearchRules.search(items, "4521234567").single().item.kind)
    }

    @Test
    fun aOneLetterQueryFindsNothing() {
        assertTrue(SearchRules.search(items, "c").isEmpty())
    }
}
