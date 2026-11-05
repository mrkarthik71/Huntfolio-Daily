package app.assistant.domain.engine.ask

import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What the User is asking "Ask Apex". Rules answer every intent except [UNKNOWN], which goes to the AI for wording. */
enum class AskIntent { SPEND, SUBSCRIPTIONS, BILLS, GOALS, TODAY, SAVE_TIPS, REMEMBER, RECALL, REMIND, UNKNOWN }

enum class AskPeriod { TODAY, WEEK, MONTH, LAST_MONTH, YEAR }

data class AskQuery(
    val intent: AskIntent,
    val period: AskPeriod = AskPeriod.MONTH,
    val category: CategoryId? = null,
    /** For REMEMBER / REMIND / RECALL: the User's own words after the command. */
    val text: String = "",
    /** For REMIND: when, if the User said one. */
    val remindAt: LocalDateTime? = null,
)

/** One debit as Ask needs it: already converted to a local date by the caller. */
data class SpendFact(val date: LocalDate, val amount: Paise, val category: CategoryId, val merchant: String?)

data class SubscriptionFact(val name: String, val monthly: Paise, val yearly: Paise, val nextDue: LocalDate, val unusedFlag: Boolean)

data class BillFact(val name: String, val amount: Paise, val dueDate: LocalDate)

data class GoalFact(val name: String, val percent: Int, val daysBehind: Int, val deadline: LocalDate?)

data class TaskFact(val title: String, val done: Boolean)

data class NoteFact(val id: Long, val text: String, val createdOn: LocalDate)

/** Everything Ask may look at, gathered once per question. All local; nothing here leaves the phone by itself. */
data class AskSnapshot(
    val today: LocalDate,
    val spends: List<SpendFact> = emptyList(),
    val subscriptions: List<SubscriptionFact> = emptyList(),
    val bills: List<BillFact> = emptyList(),
    val goals: List<GoalFact> = emptyList(),
    val todayTasks: List<TaskFact> = emptyList(),
    val notes: List<NoteFact> = emptyList(),
)

data class Amount(val label: String, val amount: Paise)

/** A saving or progress suggestion. The UI words it from [kind] and the numbers; nothing here is free text. */
data class Tip(val kind: TipKind, val subject: String = "", val amount: Paise = Paise(0), val other: Paise = Paise(0), val count: Int = 0)

enum class TipKind {
    /** [subject] category is up from [other] last month to [amount] this month. */
    CATEGORY_UP,

    /** Subscriptions cost [amount] a year across [count] services. */
    SUBSCRIPTIONS_YEARLY,

    /** [subject] subscription looked unused; cancelling saves [amount] a year. */
    UNUSED_SUBSCRIPTION,

    /** [subject] took [amount] across [count] payments this month. */
    TOP_MERCHANT,

    /** [count] small payments under ₹200 added up to [amount] this month. */
    SMALL_LEAKS,

    /** [subject] goal is [count] days behind. */
    GOAL_BEHIND,

    /** [count] bills totalling [amount] are due in the next 7 days. */
    BILLS_SOON,

    /** Nothing stands out: spending is level or down. */
    ALL_GOOD,
}

sealed interface AskAnswer {
    data class Spend(
        val period: AskPeriod,
        val category: CategoryId?,
        val total: Paise,
        val previous: Paise?,
        val count: Int,
        val byCategory: List<Amount>,
        val topMerchants: List<Amount>,
    ) : AskAnswer

    data class Subscriptions(val monthly: Paise, val yearly: Paise, val items: List<SubscriptionFact>) : AskAnswer

    data class Bills(val total: Paise, val items: List<BillFact>) : AskAnswer

    data class Goals(val items: List<GoalFact>) : AskAnswer

    data class Today(val done: Int, val total: Int, val open: List<String>) : AskAnswer

    data class Tips(val tips: List<Tip>) : AskAnswer

    data class Remember(val text: String) : AskAnswer

    data class Recall(val query: String, val notes: List<NoteFact>) : AskAnswer

    data class Remind(val text: String, val at: LocalDateTime?) : AskAnswer

    /** Rules have no answer; the caller may ask the AI with redacted facts. */
    data class Unknown(val text: String) : AskAnswer
}

/** Turns what the User typed into an [AskQuery]. Plain keyword rules, English with common Hinglish words. */
object AskParser {
    private val remember = Regex("^(remember|note|save|yaad rakh(o|na)?)\\b[:,]?\\s*(that\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val remind = Regex("^remind me\\s+(to\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val recall = Regex("^(what did i (save|note|ask you to remember)|recall|find note|notes?)\\b\\s*(about\\s+)?(.*)$", RegexOption.IGNORE_CASE)
    private val atTime = Regex("\\s+(at|by)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(tomorrow|today)?\\s*$", RegexOption.IGNORE_CASE)
    private val inTime = Regex("\\s+in\\s+(\\d{1,3})\\s*(min|mins|minutes|hour|hours|hr|hrs)\\s*$", RegexOption.IGNORE_CASE)
    private val tomorrowTail = Regex("\\s+tomorrow\\s*$", RegexOption.IGNORE_CASE)

    @Suppress("CyclomaticComplexMethod") // a flat list of keyword rules reads best in one place
    fun parse(raw: String, now: LocalDateTime): AskQuery {
        val text = raw.trim()
        val q = text.lowercase(Locale.ROOT)
        remember.find(text)?.let { return AskQuery(AskIntent.REMEMBER, text = it.groupValues[4].trim()) }
        remind.find(text)?.let { return parseRemind(it.groupValues[2].trim(), now) }
        recall.find(text)?.let { return AskQuery(AskIntent.RECALL, text = it.groupValues[4].trim().trimEnd('?')) }
        val period = periodOf(q)
        return when {
            has(q, "save", "save money", "saving", "savings", "spend less", "suggest", "improve", "tips", "advice", "cut", "reduce", "bachat") -> AskQuery(
                AskIntent.SAVE_TIPS,
            )
            has(q, "subscription", "subscriptions", "netflix", "ott", "renewal", "recurring") -> AskQuery(AskIntent.SUBSCRIPTIONS)
            has(q, "bill", "bills", "due", "emi", "rent") -> AskQuery(AskIntent.BILLS)
            has(q, "spend", "spent", "spending", "expense", "expenses", "kharcha", "where did my money", "how much did i", "money go") ->
                AskQuery(AskIntent.SPEND, period, categoryOf(q))
            categoryOf(q) != null -> AskQuery(AskIntent.SPEND, period, categoryOf(q))
            has(q, "goal", "goals", "progress", "target", "behind", "on track") -> AskQuery(AskIntent.GOALS)
            has(q, "today", "next", "plan", "schedule", "what should i do", "aaj", "agenda") -> AskQuery(AskIntent.TODAY)
            else -> AskQuery(AskIntent.UNKNOWN, text = text)
        }
    }

    @Suppress("ReturnCount") // one early return per time form ("in", "at", "tomorrow") reads clearer than nesting
    private fun parseRemind(body: String, now: LocalDateTime): AskQuery {
        inTime.find(body)?.let { m ->
            val n = m.groupValues[1].toLong()
            val at = if (m.groupValues[2].startsWith("h", ignoreCase = true)) now.plusHours(n) else now.plusMinutes(n)
            return AskQuery(AskIntent.REMIND, text = body.substring(0, m.range.first).trim(), remindAt = at.withSecond(0).withNano(0))
        }
        atTime.find(body)?.let { m ->
            var hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].ifEmpty { "0" }.toInt()
            val half = m.groupValues[4].lowercase(Locale.ROOT)
            if (half == "pm" && hour < NOON) hour += NOON
            if (half == "am" && hour == NOON) hour = 0
            if (hour !in 0..LAST_HOUR || minute !in 0..LAST_MINUTE) return AskQuery(AskIntent.REMIND, text = body)
            var at = now.toLocalDate().atTime(LocalTime.of(hour, minute))
            val day = m.groupValues[5].lowercase(Locale.ROOT)
            if (day == "tomorrow" || (day.isEmpty() && !at.isAfter(now))) at = at.plusDays(1)
            return AskQuery(AskIntent.REMIND, text = body.substring(0, m.range.first).trim(), remindAt = at)
        }
        tomorrowTail.find(body)?.let { m ->
            val at = now.toLocalDate().plusDays(1).atTime(MORNING_HOUR, 0)
            return AskQuery(AskIntent.REMIND, text = body.substring(0, m.range.first).trim(), remindAt = at)
        }
        return AskQuery(AskIntent.REMIND, text = body)
    }

    private fun has(q: String, vararg words: String) = words.any { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(q) }

    private fun periodOf(q: String): AskPeriod = when {
        has(q, "today", "aaj") -> AskPeriod.TODAY
        has(q, "week", "weekly", "7 days") -> AskPeriod.WEEK
        has(q, "last month", "previous month", "pichle mahine") -> AskPeriod.LAST_MONTH
        has(q, "year", "yearly", "annual", "this year") -> AskPeriod.YEAR
        else -> AskPeriod.MONTH
    }

    /** Matches a shipped category name or a common synonym. */
    fun categoryOf(q: String): CategoryId? {
        BuiltinCategories.NAMES.drop(1).forEach { name -> if (has(q, name.lowercase(Locale.ROOT))) return BuiltinCategories.idOf(name) }
        val synonyms = mapOf(
            "Food" to listOf("swiggy", "zomato", "eating out", "restaurant", "restaurants", "khana"),
            "Groceries" to listOf("grocery", "blinkit", "zepto", "bigbasket", "vegetables"),
            "Transport" to listOf("uber", "ola", "rapido", "cab", "cabs", "metro", "auto"),
            "Shopping" to listOf("amazon", "flipkart", "myntra", "clothes"),
            "Fuel" to listOf("petrol", "diesel"),
            "Health" to listOf("medicine", "medicines", "doctor", "pharmacy", "gym"),
            "Entertainment" to listOf("movies", "movie", "games"),
        )
        synonyms.forEach { (name, words) -> if (words.any { has(q, it) }) return BuiltinCategories.idOf(name) }
        return null
    }

    private const val NOON = 12
    private const val LAST_HOUR = 23
    private const val LAST_MINUTE = 59
    private const val MORNING_HOUR = 9
}

/**
 * Answers an [AskQuery] from the [AskSnapshot] with rules only (CLAUDE.md: rules and code first).
 * Pure and deterministic; amounts stay in [Paise].
 */
object AskEngine {
    private const val TOP = 5
    private const val SMALL_PAYMENT_PAISE = 20_000L
    private const val SMALL_LEAK_MIN_COUNT = 8
    private const val RISE_PERCENT = 20
    private const val HUNDRED = 100
    private const val BILL_WINDOW_DAYS = 7L
    private const val MAX_TIPS = 5

    fun answer(query: AskQuery, s: AskSnapshot): AskAnswer = when (query.intent) {
        AskIntent.SPEND -> spend(query.period, query.category, s)
        AskIntent.SUBSCRIPTIONS -> subscriptions(s)
        AskIntent.BILLS -> AskAnswer.Bills(sum(s.upcomingBills().map { it.amount }), s.upcomingBills())
        AskIntent.GOALS -> AskAnswer.Goals(s.goals.sortedWith(compareByDescending<GoalFact> { it.daysBehind }.thenBy { it.percent }))
        AskIntent.TODAY -> AskAnswer.Today(s.todayTasks.count { it.done }, s.todayTasks.size, s.todayTasks.filterNot { it.done }.map { it.title })
        AskIntent.SAVE_TIPS -> AskAnswer.Tips(tips(s))
        AskIntent.REMEMBER -> AskAnswer.Remember(query.text)
        AskIntent.RECALL -> AskAnswer.Recall(query.text, recall(query.text, s.notes))
        AskIntent.REMIND -> AskAnswer.Remind(query.text, query.remindAt)
        AskIntent.UNKNOWN -> AskAnswer.Unknown(query.text)
    }

    /** The inclusive date range a period covers, as of [today]. */
    fun range(period: AskPeriod, today: LocalDate): ClosedRange<LocalDate> = when (period) {
        AskPeriod.TODAY -> today..today
        AskPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))..today
        AskPeriod.MONTH -> today.withDayOfMonth(1)..today
        AskPeriod.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1)..today.withDayOfMonth(1).minusDays(1)
        AskPeriod.YEAR -> today.withDayOfYear(1)..today
    }

    /** The same stretch one period earlier (month-to-date compares with the same days of last month). */
    private fun previousRange(period: AskPeriod, today: LocalDate): ClosedRange<LocalDate>? = when (period) {
        AskPeriod.TODAY -> today.minusDays(1)..today.minusDays(1)
        AskPeriod.WEEK -> range(period, today).let { it.start.minusWeeks(1)..it.endInclusive.minusWeeks(1) }
        AskPeriod.MONTH -> today.minusMonths(1).let { it.withDayOfMonth(1)..it }
        AskPeriod.LAST_MONTH -> range(AskPeriod.LAST_MONTH, today.minusMonths(1))
        AskPeriod.YEAR -> null
    }

    fun spend(period: AskPeriod, category: CategoryId?, s: AskSnapshot): AskAnswer.Spend {
        val r = range(period, s.today)
        val inRange = s.spends.filter { it.date in r && (category == null || it.category == category) }
        val prev = previousRange(period, s.today)?.let { p ->
            sum(s.spends.filter { it.date in p && (category == null || it.category == category) }.map { it.amount })
        }
        return AskAnswer.Spend(
            period = period,
            category = category,
            total = sum(inRange.map { it.amount }),
            previous = prev,
            count = inRange.size,
            byCategory = grouped(inRange) { categoryName(it.category) },
            topMerchants = grouped(inRange.filter { it.merchant != null }) { it.merchant.orEmpty() },
        )
    }

    private fun subscriptions(s: AskSnapshot): AskAnswer.Subscriptions =
        AskAnswer.Subscriptions(sum(s.subscriptions.map { it.monthly }), sum(s.subscriptions.map { it.yearly }), s.subscriptions.sortedBy { it.nextDue })

    private fun AskSnapshot.upcomingBills(): List<BillFact> = bills.filter { !it.dueDate.isBefore(today) }.sortedBy { it.dueDate }

    /** The suggestions, most valuable first. Every number comes from the snapshot. */
    fun tips(s: AskSnapshot): List<Tip> {
        val month = range(AskPeriod.MONTH, s.today)
        val lastSameDays = previousRange(AskPeriod.MONTH, s.today)!!
        val now = s.spends.filter { it.date in month }
        val before = s.spends.filter { it.date in lastSameDays }
        val tips = mutableListOf<Tip>()
        s.subscriptions.filter { it.unusedFlag }.sortedByDescending { it.yearly.value }
            .forEach { tips += Tip(TipKind.UNUSED_SUBSCRIPTION, it.name, it.yearly) }
        val beforeByCat = before.groupBy { it.category }.mapValues { (_, v) -> sum(v.map { it.amount }) }
        now.groupBy { it.category }.mapValues { (_, v) -> sum(v.map { it.amount }) }
            .mapNotNull { (cat, total) ->
                val was = beforeByCat[cat] ?: return@mapNotNull null
                val risen = was.value > 0 && total.value * HUNDRED >= was.value * (HUNDRED + RISE_PERCENT)
                if (risen) Tip(TipKind.CATEGORY_UP, categoryName(cat), total, was) else null
            }
            .sortedByDescending { it.amount.value - it.other.value }
            .forEach { tips += it }
        val small = now.filter { it.amount.value < SMALL_PAYMENT_PAISE }
        if (small.size >= SMALL_LEAK_MIN_COUNT) tips += Tip(TipKind.SMALL_LEAKS, amount = sum(small.map { it.amount }), count = small.size)
        now.filter { it.merchant != null }.groupBy { it.merchant!! }.maxByOrNull { (_, v) -> v.sumOf { it.amount.value } }?.let { (name, v) ->
            if (v.size > 1) tips += Tip(TipKind.TOP_MERCHANT, name, sum(v.map { it.amount }), count = v.size)
        }
        if (s.subscriptions.isNotEmpty()) {
            tips += Tip(TipKind.SUBSCRIPTIONS_YEARLY, amount = sum(s.subscriptions.map { it.yearly }), count = s.subscriptions.size)
        }
        val soon = s.bills.filter { !it.dueDate.isBefore(s.today) && !it.dueDate.isAfter(s.today.plusDays(BILL_WINDOW_DAYS)) }
        if (soon.isNotEmpty()) tips += Tip(TipKind.BILLS_SOON, amount = sum(soon.map { it.amount }), count = soon.size)
        s.goals.filter { it.daysBehind >= 1 }.sortedByDescending { it.daysBehind }.take(2)
            .forEach { tips += Tip(TipKind.GOAL_BEHIND, it.name, count = it.daysBehind) }
        if (tips.isEmpty()) tips += Tip(TipKind.ALL_GOOD)
        return tips.take(MAX_TIPS)
    }

    /** Notes containing every word of [query] (case-insensitive), newest first; all notes when the query is blank. */
    fun recall(query: String, notes: List<NoteFact>): List<NoteFact> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 1 && it !in STOP }
        return notes.filter { n ->
            val t = n.text.lowercase(Locale.ROOT)
            words.all { it in t }
        }
            .sortedWith(compareByDescending<NoteFact> { it.createdOn }.thenByDescending { it.id })
    }

    private val STOP = setOf("the", "my", "a", "an", "is", "of", "to", "about", "what", "did", "i", "me")

    fun categoryName(id: CategoryId): String = BuiltinCategories.NAMES.getOrNull((id.value - 1).toInt()) ?: BuiltinCategories.UNCATEGORISED_NAME

    private fun grouped(facts: List<SpendFact>, key: (SpendFact) -> String): List<Amount> =
        facts.groupBy(key).map { (k, v) -> Amount(k, sum(v.map { it.amount })) }.sortedByDescending { it.amount.value }.take(TOP)

    private fun sum(xs: List<Paise>): Paise = xs.fold(Paise(0)) { a, b -> a + b }
}
