package app.assistant.domain.engine.ask

import app.assistant.domain.model.Paise
import java.time.LocalDate
import java.util.Locale

/** A change the User asked Ask Apex to make. The words are the User's own command, so it is applied straight away and shown back. */
sealed interface AskAction {
    data class LogSpend(val amount: Paise, val what: String) : AskAction

    data class AddToList(val list: String, val items: List<String>) : AskAction

    data class AddTask(val title: String, val on: LocalDate) : AskAction

    data class MoveTask(val query: String, val to: LocalDate) : AskAction

    data class Talked(val name: String) : AskAction

    data class Journal(val text: String) : AskAction
}

/**
 * Recognises commands in English and Hinglish: "spent 450 on lunch", "add milk and eggs to groceries",
 * "add task call the bank tomorrow", "move report to friday", "talked to Amma", "journal: good day".
 * Returns null for anything else, which then goes to the question rules.
 */
object AskActions {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val AMOUNT = "([0-9][0-9,]{0,9}(?:\\.[0-9]{1,2})?)"
    private val spent =
        Regex(
            "^(?:i\\s+)?(?:spent|paid|spend|gave|diye|kharch(?:a|e)?(?:\\s+kiye?)?)\\s+(?:rs\\.?|₹|inr)?\\s*$AMOUNT" +
                "\\s*(?:rs|rupees|/-)?\\s*(?:on|for|at|pe|par|ke liye)?\\s*(.*)$",
            opt,
        )
    private val spentAfter =
        Regex("^(.+?)\\s+(?:pe|par|ke liye|for|on)\\s+(?:rs\\.?|₹)?\\s*$AMOUNT\\s*(?:rs|rupees)?\\s*(?:spent|paid|diye|kharch(?:a|e)?(?:\\s+kiye?)?)$", opt)
    private val toList = Regex("^(?:add|put)\\s+(.+?)\\s+(?:to|in|on)\\s+(?:my\\s+|the\\s+)?(.+?)(?:\\s+list)?$", opt)
    private val buy = Regex("^(?:buy|need to buy|lena hai|kharidna hai)\\s+(.+)$", opt)
    private val task = Regex("^(?:add (?:a )?task|new task|todo|to-do|task)\\s*[:,-]?\\s+(.+)$", opt)
    private val move = Regex("^(?:move|shift|push|postpone|reschedule)\\s+(.+?)\\s+to\\s+(.+)$", opt)
    private val talked = Regex("^(?:i\\s+)?(?:talked|spoke|called|met|caught up)\\s+(?:to|with)?\\s*(.+)$", opt)
    private val journal = Regex("^(?:journal|dear diary|diary)\\s*[:,-]?\\s+(.+)$", opt)
    private val dayTail =
        Regex(
            "\\s+(today|tomorrow|next week|in \\d{1,3} days?|" +
                "(?:on |by |this )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun))\\s*$",
            opt,
        )
    private val itemSplit = Regex("\\s*(?:,|\\band\\b|&|\\baur\\b)\\s*", opt)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one early return per command form, in priority order
    fun parse(raw: String, today: LocalDate): AskAction? {
        val text = raw.trim().trimEnd('.', '!')
        journal.find(text)?.let { return AskAction.Journal(it.groupValues[1].trim()) }
        spent.find(text)?.let { m -> amount(m.groupValues[1])?.let { return AskAction.LogSpend(it, m.groupValues[2].trim().ifBlank { "" }) } }
        spentAfter.find(text)?.let { m -> amount(m.groupValues[2])?.let { return AskAction.LogSpend(it, m.groupValues[1].trim()) } }
        task.find(text)?.let { m ->
            val (title, day) = splitDay(m.groupValues[1], today)
            if (title.isNotBlank()) return AskAction.AddTask(title, day ?: today)
        }
        move.find(text)?.let { m ->
            val to =
                AskParser.dayFrom(m.groupValues[2].trim().lowercase(Locale.ROOT), today) ?: if (m.groupValues[2].trim().equals("today", true)) today else null
            if (to != null) return AskAction.MoveTask(m.groupValues[1].trim(), to)
        }
        toList.find(text)?.let { m ->
            val items = items(m.groupValues[1])
            if (items.isNotEmpty()) return AskAction.AddToList(listName(m.groupValues[2]), items)
        }
        buy.find(text)?.let { m ->
            val items = items(m.groupValues[1])
            if (items.isNotEmpty()) return AskAction.AddToList(GROCERIES, items)
        }
        talked.find(text)?.let { m -> m.groupValues[1].trim().takeIf { it.isNotBlank() && it.length <= NAME_MAX }?.let { return AskAction.Talked(it) } }
        return null
    }

    /** "call the bank tomorrow" → ("call the bank", tomorrow). */
    private fun splitDay(s: String, today: LocalDate): Pair<String, LocalDate?> {
        val m = dayTail.find(s) ?: return s.trim() to null
        val phrase = m.groupValues[1].lowercase(Locale.ROOT)
        val day = if (phrase == "today") today else AskParser.dayFrom(phrase, today)
        return if (day == null) s.trim() to null else s.substring(0, m.range.first).trim() to day
    }

    private fun items(s: String): List<String> = s.split(itemSplit).map { it.trim() }.filter { it.isNotBlank() }.take(MAX_ITEMS)

    private fun listName(s: String): String = when (s.trim().lowercase(Locale.ROOT)) {
        "grocery", "groceries", "grocery list", "kirana", "sabzi" -> GROCERIES
        "shopping" -> "Shopping"
        else -> s.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }.take(NAME_MAX)
    }

    private fun amount(s: String): Paise? {
        val clean = s.replace(",", "")
        val parts = clean.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        val paise = rupees * HUNDRED + frac
        return if (paise in 1..MAX_PAISE) Paise(paise) else null
    }

    const val GROCERIES = "Groceries"
    private const val HUNDRED = 100L
    private const val MAX_PAISE = 1_000_000_000L
    private const val MAX_ITEMS = 12
    private const val NAME_MAX = 60
}
