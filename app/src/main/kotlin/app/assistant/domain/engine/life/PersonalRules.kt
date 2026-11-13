package app.assistant.domain.engine.life

import app.assistant.domain.model.KeepInTouch
import app.assistant.domain.model.ListItem
import app.assistant.domain.model.Note
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Lists: a done item with a repeat comes back once its repeat has passed (milk every 3 days). */
object ListRules {
    fun isOpen(item: ListItem, today: LocalDate): Boolean = !item.done || dueAgain(item, today)

    fun dueAgain(item: ListItem, today: LocalDate): Boolean {
        val every = item.repeatDays ?: return false
        val on = item.doneOn ?: return true
        return !today.isBefore(on.plusDays(every.toLong()))
    }

    /** Open items first in each list, then the rest; lists in name order. */
    fun grouped(items: List<ListItem>, today: LocalDate): List<Pair<String, List<ListItem>>> = items.groupBy { it.list }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (name, l) -> name to l.sortedWith(compareBy<ListItem> { !isOpen(it, today) }.thenBy { it.title.lowercase() }) }
}

/** Keep in touch: who is due for a call or message. */
object KeepInTouchRules {
    fun daysSince(p: KeepInTouch, today: LocalDate): Long? = p.lastTalked?.let { ChronoUnit.DAYS.between(it, today) }

    fun isDue(p: KeepInTouch, today: LocalDate): Boolean = daysSince(p, today)?.let { it >= p.everyDays } ?: true

    /** Most overdue first; people never marked come first of all. */
    fun ordered(people: List<KeepInTouch>, today: LocalDate): List<KeepInTouch> = people.sortedWith(
        compareByDescending<KeepInTouch> { isDue(it, today) }
            .thenByDescending { p -> daysSince(p, today)?.let { it - p.everyDays } ?: Long.MAX_VALUE }
            .thenBy { it.name.lowercase() },
    )
}

/** Notes search: every word must appear in the title or body, ignoring case. */
object NoteSearch {
    fun matches(note: Note, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = (note.title + " " + note.body).lowercase()
        return words.all { it in hay }
    }
}
