package app.assistant.domain.engine.search

import java.time.LocalDate

/** Where a result lives; each kind opens its own screen. */
enum class SearchKind { NOTE, TASK, LIST, JOURNAL, PERSON, TRIP, ORDER, PAYMENT, BILL, MEMORY, DATE }

/** One searchable thing: [title] and [body] are matched; [date] orders recent things first. */
data class Searchable(val kind: SearchKind, val title: String, val body: String = "", val date: LocalDate? = null)

data class SearchHit(val item: Searchable, val score: Int)

/**
 * Search across everything Apex keeps, on the phone. Every word of the query must appear (in any
 * order, ignoring case and accents); a word at the start of the title scores most, then anywhere in
 * the title, then in the body. Ties go to the more recent item.
 */
object SearchRules {
    const val MIN_QUERY = 2
    const val MAX_RESULTS = 60
    private const val TITLE_START = 5
    private const val TITLE_WORD = 3
    private const val BODY_WORD = 1

    fun search(items: List<Searchable>, query: String): List<SearchHit> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || query.trim().length < MIN_QUERY) return emptyList()
        return items.mapNotNull { item ->
            val title = fold(item.title)
            val body = fold(item.body)
            var score = 0
            for (w in words) {
                score += when {
                    title.startsWith(w) || title.contains(" $w") -> TITLE_START
                    title.contains(w) -> TITLE_WORD
                    body.contains(w) -> BODY_WORD
                    else -> return@mapNotNull null
                }
            }
            SearchHit(item, score)
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.item.date ?: LocalDate.MIN })
            .take(MAX_RESULTS)
    }

    /** Lower case, without accents, so "cafe" finds "Café". */
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
