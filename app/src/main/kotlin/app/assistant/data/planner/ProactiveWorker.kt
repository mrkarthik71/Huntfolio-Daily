package app.assistant.data.planner

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.assistant.R
import app.assistant.domain.engine.schedule.DayFacts
import app.assistant.domain.engine.schedule.FreeTime
import app.assistant.domain.engine.schedule.GoalFacts
import app.assistant.domain.engine.schedule.ProactiveRules
import app.assistant.domain.engine.schedule.RenewalFacts
import app.assistant.domain.engine.schedule.Span
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.CardKind
import app.assistant.domain.model.EnergyTag
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.CardDraft
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.PlannerRepository
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.SubscriptionRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The hourly proactive agent (Module E). It gathers facts on the phone (tasks, calendar, goals,
 * subscriptions), runs the pure [ProactiveRules], and stores one card per kind and day. It never
 * changes a plan: cards only propose, and they appear on Today with their reason.
 */
@HiltWorker
class ProactiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tasks: TaskRepository,
    private val calendar: CalendarRepository,
    private val goals: GoalRepository,
    private val subscriptions: SubscriptionRepository,
    private val planner: PlannerRepository,
    private val settings: SettingsStore,
    private val time: TimeSource,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { run() }
        return Result.success()
    }

    private suspend fun run() {
        val today = time.today()
        val zone = time.zone()
        val wh = settings.current().workingHours
        val working = if (wh.spansMidnight) Span(wh.start.toSecondOfDay() / SEC, MINUTES_PER_DAY) else Span.of(wh.start, wh.end)
        val nowMinute = time.now().atZone(zone).toLocalTime().toSecondOfDay() / SEC

        suspend fun facts(date: LocalDate, fromMinute: Int): DayFacts {
            val meetings = calendar.eventsOn(date).first().timed.mapNotNull { it.timed }.map { r ->
                val s = r.start.atZone(zone)
                val e = r.end.atZone(zone)
                val start = if (s.toLocalDate().isBefore(date)) 0 else s.toLocalTime().toSecondOfDay() / SEC
                val end = if (e.toLocalDate().isAfter(date)) MINUTES_PER_DAY else e.toLocalTime().toSecondOfDay() / SEC
                Span(start, maxOf(start, end))
            }
            val window = Span(fromMinute.coerceIn(working.start, working.end), working.end)
            val free = FreeTime.of(window, meetings)
            val taskMinutes = tasks.plannedOn(date).first().filter { it.status == TaskStatus.OPEN }.sumOf { it.estimateMinutes ?: DEFAULT_TASK_MIN }
            val meetingMinutes = working.minutes - FreeTime.of(working, meetings).sumOf { it.minutes }
            return DayFacts(date, taskMinutes + meetingMinutes, working.minutes, free.maxOfOrNull { it.minutes } ?: 0)
        }

        val days = listOf(facts(today, nowMinute), facts(today.plusDays(1), working.start))
        val open = tasks.openTasks().first()
        val goalFacts = goals.statusesNow(today).map { s ->
            GoalFacts(s.goal.id.value, s.goal.name, s.goal.deadline, open.filter { it.goalId == s.goal.id }.sumOf { it.estimateMinutes ?: DEFAULT_TASK_MIN })
        }
        val renewals = subscriptions.active().first().map { RenewalFacts(it.merchant, MoneyFormatter.format(it.snapshot.amount), it.snapshot.nextDue) }
        val signals = ProactiveRules.evaluate(
            today = today,
            days = days,
            goals = goalFacts,
            renewals = renewals,
            averageFreeMinutesPerDay = (working.minutes * FREE_SHARE_PERCENT) / PERCENT,
            openDeepWork = open.any { it.energy == EnergyTag.DEEP_WORK },
        )
        val ctx = applicationContext
        val drafts = signals.map { s ->
            when (s.kind) {
                CardKind.OVERLOAD -> CardDraft(
                    s.kind,
                    s.forDate,
                    ctx.getString(R.string.card_overload_title),
                    ctx.getString(R.string.card_overload_body, hm(s.numbers[0]), hm(s.numbers[1])),
                )
                CardKind.GOAL_AT_RISK -> CardDraft(
                    s.kind,
                    s.forDate,
                    ctx.getString(R.string.card_goal_title, s.subject),
                    ctx.getString(R.string.card_goal_body, hm(s.numbers[0]), hm(s.numbers[1]), s.numbers[2]),
                )
                CardKind.RENEWAL_SOON -> CardDraft(
                    s.kind,
                    s.forDate,
                    ctx.getString(R.string.card_renewal_title, s.subject),
                    if (s.numbers[0] == 0) ctx.getString(R.string.card_renewal_today) else ctx.getString(R.string.card_renewal_body, s.numbers[0]),
                )
                CardKind.FREE_SLOT -> CardDraft(
                    s.kind,
                    s.forDate,
                    ctx.getString(R.string.card_free_title, hm(s.numbers[0])),
                    ctx.getString(R.string.card_free_body),
                )
                CardKind.ROUTINE_MISSED -> CardDraft(s.kind, s.forDate, s.subject, "")
            }
        }
        planner.offerCards(drafts, time.now())
    }

    private fun hm(minutes: Int): String = if (minutes >= MIN_PER_HOUR) {
        applicationContext.getString(R.string.duration_h_m, minutes / MIN_PER_HOUR, minutes % MIN_PER_HOUR)
    } else {
        applicationContext.getString(R.string.duration_m, minutes)
    }

    companion object {
        private const val UNIQUE_NAME = "proactive-agent"
        private const val SEC = 60
        private const val MIN_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 1_440
        private const val DEFAULT_TASK_MIN = 30
        private const val FREE_SHARE_PERCENT = 60
        private const val PERCENT = 100

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProactiveWorker>(1, TimeUnit.HOURS).build(),
            )
        }
    }
}
