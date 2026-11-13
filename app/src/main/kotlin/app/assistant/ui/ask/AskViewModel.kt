package app.assistant.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.ask.AskAnswer
import app.assistant.domain.engine.ask.AskEngine
import app.assistant.domain.engine.ask.AskParser
import app.assistant.domain.engine.ask.AskSnapshot
import app.assistant.domain.engine.ask.BillFact
import app.assistant.domain.engine.ask.GoalFact
import app.assistant.domain.engine.ask.NoteFact
import app.assistant.domain.engine.ask.SpendFact
import app.assistant.domain.engine.ask.SubscriptionFact
import app.assistant.domain.engine.ask.TaskFact
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.PromptType
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.model.SmartReminder
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.AdviceItem
import app.assistant.domain.port.Advisor
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.MemoryRepository
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.ReminderScheduler
import app.assistant.domain.port.ReviewAdvice
import app.assistant.domain.port.ReviewFacts
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.SubscriptionRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The AI part of a turn: only used when rules have no answer. */
sealed interface AiPart {
    data object None : AiPart

    data object Thinking : AiPart

    data class Wording(val summary: String?, val suggestions: List<String>) : AiPart

    /** AI is off, offline or capped: the screen says so and offers what rules can answer. */
    data object Unavailable : AiPart
}

data class AskTurn(val id: Int, val question: String, val answer: AskAnswer, val ai: AiPart = AiPart.None, val saved: Boolean = false)

data class AskUi(val turns: List<AskTurn> = emptyList(), val busy: Boolean = false)

/**
 * "Ask Apex": answers from local facts with [AskEngine] first. Only a question the rules cannot
 * answer goes to the AI, as a redacted payload of the question and a few totals (CLAUDE.md:
 * rules and code first; AI only writes wording). "Remember" and "remind me" save straight away,
 * because the User asked for exactly that change.
 */
@HiltViewModel
@Suppress("LongParameterList") // one read port per fact source Ask can answer from
class AskViewModel @Inject constructor(
    private val time: TimeSource,
    private val transactions: TransactionRepository,
    private val subscriptions: SubscriptionRepository,
    private val bills: BillRepository,
    private val goals: GoalRepository,
    private val tasks: TaskRepository,
    private val memory: MemoryRepository,
    private val planner: PlannerRepository,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsStore,
    private val advisor: Advisor,
    private val dates: app.assistant.domain.port.DateStore,
    private val habitStore: app.assistant.domain.port.HabitStore,
    private val followUps: app.assistant.domain.port.FollowUpStore,
    private val notify: app.assistant.ui.life.NotifySwitch,
    private val personal: app.assistant.domain.port.PersonalStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(AskUi())
    val ui: StateFlow<AskUi> = _ui.asStateFlow()
    private var nextId = 0

    /** Voice: of the recogniser's guesses, asks the one Ask can act on (see [app.assistant.domain.engine.ask.SpokenText.best]). */
    fun askSpoken(guesses: List<String>) {
        val now = time.now().atZone(time.zone()).toLocalDateTime()
        app.assistant.domain.engine.ask.SpokenText.best(guesses, now)?.let(::ask)
    }

    fun ask(raw: String) {
        val text = app.assistant.domain.engine.ask.SpokenText.normalise(raw)
        if (text.isEmpty() || _ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val zone = time.zone()
            val now = time.now().atZone(zone).toLocalDateTime()
            // A typed question mark means a question, never a command ("buy or rent?").
            val action = if (raw.trim().endsWith("?")) null else app.assistant.domain.engine.ask.AskActions.parse(text, now.toLocalDate())
            val answer = if (action != null) carryOut(action) else AskEngine.answer(AskParser.parse(text, now), snapshot())
            val id = nextId++
            var saved = false
            when (answer) {
                is AskAnswer.Remember -> if (answer.text.isNotBlank()) {
                    memory.add(answer.text, time.now())
                    saved = true
                }
                is AskAnswer.Remind -> if (answer.at != null && answer.text.isNotBlank()) {
                    planner.saveReminder(SmartReminder(ReminderId(0), answer.text, ReminderKind.TIME, fireAt = answer.at.atZone(zone).toInstant()))
                    settings.update { it.copy(enabledTypes = it.enabledTypes + NotificationType.SMART_REMINDER) }
                    scheduler.sync()
                    saved = true
                }
                is AskAnswer.FollowUp -> if (answer.person.isNotBlank()) {
                    followUps.save(app.assistant.domain.model.FollowUp(0, answer.person, answer.about, answer.nudgeOn, done = false, createdOn = time.today()))
                    if (NotificationType.FOLLOW_UP !in settings.current().enabledTypes) notify.set(NotificationType.FOLLOW_UP, true) else notify.replan()
                    saved = true
                }
                else -> Unit
            }
            val pending = if (answer is AskAnswer.Unknown) AiPart.Thinking else AiPart.None
            _ui.update { it.copy(turns = it.turns + AskTurn(id, text, answer, pending, saved), busy = answer is AskAnswer.Unknown) }
            if (answer is AskAnswer.Unknown) {
                val ai = aiAnswer(text)
                _ui.update { s -> s.copy(turns = s.turns.map { if (it.id == id) it.copy(ai = ai) else it }, busy = false) }
            }
        }
    }

    /** Carries out a command the User typed or said; the answer shows exactly what changed. */
    @Suppress("CyclomaticComplexMethod") // one branch per command
    private suspend fun carryOut(a: app.assistant.domain.engine.ask.AskAction): AskAnswer.Did {
        val today = time.today()
        val now = time.now()
        return when (a) {
            is app.assistant.domain.engine.ask.AskAction.LogSpend -> {
                val merchant = a.what.ifBlank { "Cash" }.take(app.assistant.domain.engine.transactions.TransactionValidator.MERCHANT_MAX)
                val r = transactions.addManual(
                    app.assistant.domain.engine.transactions.ManualTransactionInput(app.assistant.domain.model.TxnType.DEBIT, a.amount, now, merchant),
                )
                AskAnswer.Did(a, r is app.assistant.domain.error.Outcome.Ok)
            }
            is app.assistant.domain.engine.ask.AskAction.AddToList -> {
                a.items.forEach { personal.addListItem(a.list, it, null) }
                AskAnswer.Did(a, true)
            }
            is app.assistant.domain.engine.ask.AskAction.AddTask -> {
                val r = tasks.save(app.assistant.domain.engine.tasks.TaskInput(a.title, plannedDate = a.on), now = now, today = today)
                if (r is app.assistant.domain.error.Outcome.Ok && a.repeat != null) tasks.setRepeat(r.value, a.repeat)
                AskAnswer.Did(a, r is app.assistant.domain.error.Outcome.Ok)
            }
            is app.assistant.domain.engine.ask.AskAction.Owe -> {
                personal.addIou(a.person, a.amount, a.theyOwe, a.note, today)
                AskAnswer.Did(a, true)
            }
            is app.assistant.domain.engine.ask.AskAction.Settle -> {
                val n = personal.settlePerson(a.person, today)
                AskAnswer.Did(a, n > 0)
            }
            is app.assistant.domain.engine.ask.AskAction.MoveTask -> {
                val q = a.query.lowercase()
                val match = tasks.openTasks().first().firstOrNull { it.title.lowercase().contains(q) }
                val ok = match != null && tasks.move(match.id, a.to, today) is app.assistant.domain.error.Outcome.Ok
                AskAnswer.Did(a, ok, match?.title.orEmpty())
            }
            is app.assistant.domain.engine.ask.AskAction.Talked -> {
                val name = a.name.lowercase()
                val person = personal.people().first().firstOrNull { it.name.lowercase() == name || it.name.lowercase().startsWith(name) }
                if (person != null) personal.talkedTo(person.id, today)
                AskAnswer.Did(a, person != null, person?.name.orEmpty())
            }
            is app.assistant.domain.engine.ask.AskAction.Journal -> {
                val old = personal.journal().first().firstOrNull { it.date == today }?.text
                personal.saveJournal(today, listOfNotNull(old, a.text).joinToString("\n"))
                AskAnswer.Did(a, true)
            }
        }
    }

    fun clear() = _ui.update { AskUi() }

    private suspend fun aiAnswer(question: String): AiPart {
        val s = snapshot()
        val month = AskEngine.spend(app.assistant.domain.engine.ask.AskPeriod.MONTH, null, s)
        val facts = ReviewFacts(
            items = listOf(AdviceItem("q1", question)),
            numbers = mapOf(
                "spent_this_month" to MoneyFormatter.format(month.total),
                "payments_this_month" to month.count.toString(),
                "subscriptions" to s.subscriptions.size.toString(),
                "open_tasks_today" to s.todayTasks.count { !it.done }.toString(),
                "goals_behind" to s.goals.count { it.daysBehind > 0 }.toString(),
            ),
            summaryLine = "q1",
        )
        return when (val r = advisor.review(PromptType.ASSISTANT_ANSWER, facts)) {
            is ReviewAdvice.Wording -> AiPart.Wording(r.summary, r.suggestions.map { it.text })
            is ReviewAdvice.Unavailable -> AiPart.Unavailable
        }
    }

    private suspend fun snapshot(): AskSnapshot {
        val zone = time.zone()
        val today = time.today()
        val from = today.minusMonths(2).withDayOfMonth(1)
        val spends = runCatching {
            transactions.transactions().first()
                .filter { it.type == TxnType.DEBIT && !it.isReversal }
                .map { SpendFact(it.at.atZone(zone).toLocalDate(), it.amount, it.category, it.merchantName) }
                .filter { !it.date.isBefore(from) }
        }.getOrDefault(emptyList())
        val due = runCatching { subscriptions.dueUsageChecks().first().map { it.subscriptionId }.toSet() }.getOrDefault(emptySet())
        val subs = runCatching {
            subscriptions.active().first().map { v ->
                SubscriptionFact(
                    v.merchant,
                    app.assistant.domain.model.Paise(v.yearlyCost.value / MONTHS),
                    v.yearlyCost,
                    v.snapshot.nextDue,
                    v.snapshot.id in due,
                )
            }
        }.getOrDefault(emptyList())
        val billFacts = runCatching { bills.bills().first().map { BillFact(it.name, it.amount, it.dueDate) } }.getOrDefault(emptyList())
        val goalFacts = runCatching {
            goals.statusesNow(today).map { GoalFact(it.goal.name, it.percent, it.daysBehind, it.goal.deadline) }
        }.getOrDefault(emptyList())
        val taskFacts = runCatching {
            tasks.plannedOn(today).first().filter { it.status != TaskStatus.DROPPED }.map { TaskFact(it.title, it.status == TaskStatus.DONE) }
        }.getOrDefault(emptyList())
        val notes = runCatching {
            memory.notes().first().map { NoteFact(it.id, it.text, it.createdAt.atZone(zone).toLocalDate()) }
        }.getOrDefault(emptyList())
        val dateFacts = runCatching {
            dates.datesNow().mapNotNull { d ->
                val next = app.assistant.domain.engine.life.DateRules.nextOccurrence(d, today) ?: return@mapNotNull null
                app.assistant.domain.engine.ask.DateFact(d.title, d.kind, next, java.time.temporal.ChronoUnit.DAYS.between(today, next))
            }
        }.getOrDefault(emptyList())
        val habitFacts = runCatching {
            val hs = habitStore.habitsNow().filter { it.active }
            val checks = habitStore.checks(today.minusDays(HABIT_LOOKBACK), today).first()
            hs.map { h ->
                val (d, t) = app.assistant.domain.engine.life.HabitRules.dayProgress(listOf(h), checks, today)
                app.assistant.domain.engine.ask.HabitFact(h.name, d, t, app.assistant.domain.engine.life.HabitRules.streak(h, checks, today))
            }
        }.getOrDefault(emptyList())
        return AskSnapshot(today, spends, subs, billFacts, goalFacts, taskFacts, notes, dateFacts, habitFacts)
    }

    private companion object {
        const val MONTHS = 12
        const val HABIT_LOOKBACK = 120L
    }
}
