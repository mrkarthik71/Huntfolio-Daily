package app.assistant.notify

import android.content.Context
import app.assistant.R
import app.assistant.domain.engine.life.DateRules
import app.assistant.domain.model.DateKind
import app.assistant.domain.model.HabitKind
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.FollowUpStore
import app.assistant.domain.port.HabitStore
import app.assistant.domain.port.QueuedAlarm
import app.assistant.domain.port.ReflectionStore
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the text of the whole-day notifications when they fire (so an edited or finished item is
 * shown as it is now, or not at all) and applies their "Done" action.
 */
@Singleton
class LifeNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val habits: HabitStore,
    private val dates: DateStore,
    private val followUps: FollowUpStore,
    private val reflection: ReflectionStore,
    private val time: TimeSource,
) {
    /** Null means there is nothing to say any more (done, deleted or switched off). */
    suspend fun content(row: QueuedAlarm): NotificationContent? = when (row.type) {
        NotificationType.HABIT_CHECK -> habit(row.refKey)
        NotificationType.IMPORTANT_DATE -> date(row.refKey)
        NotificationType.FOLLOW_UP -> followUp(row.refKey)
        NotificationType.FOCUS_END -> focus(row.refKey)
        NotificationType.WEEKLY_PLAN -> NotificationContent(context.getString(R.string.week_notif_title), context.getString(R.string.week_notif_text), "weekplan")
        else -> null
    }

    private suspend fun habit(ref: String): NotificationContent? {
        val r = LifeRefs.parseHabit(ref) ?: return null
        val h = habits.get(r.id)?.takeIf { it.active } ?: return null
        val done = habits.checks(r.date, r.date).first().any { it.habitId == h.id && it.slot == r.slot }
        if (done) return null
        val title = if (h.kind ==
            HabitKind.MEDICINE
        ) {
            context.getString(R.string.habit_notif_medicine, h.name)
        } else {
            context.getString(R.string.habit_notif_title, h.name)
        }
        return NotificationContent(title, context.getString(R.string.habit_notif_text), "plan", actionRef = ref)
    }

    private suspend fun date(ref: String): NotificationContent? {
        val (id, occurrence, lead) = LifeRefs.parseDate(ref) ?: return null
        val d = dates.get(id) ?: return null
        val whenText = when (lead) {
            0 -> context.getString(R.string.date_when_today)
            1 -> context.getString(R.string.date_when_tomorrow)
            else -> context.getString(R.string.date_when_in_days, lead)
        }
        val years = DateRules.yearsAt(d, occurrence.minusDays(lead.toLong()))
        val title = when (d.kind) {
            DateKind.BIRTHDAY -> if (years !=
                null
            ) {
                context.getString(R.string.date_notif_birthday_age, d.title, years, whenText)
            } else {
                context.getString(R.string.date_notif_birthday, d.title, whenText)
            }
            DateKind.ANNIVERSARY -> context.getString(R.string.date_notif_anniversary, d.title, whenText)
            DateKind.DOCUMENT, DateKind.INSURANCE, DateKind.WARRANTY -> context.getString(R.string.date_notif_expiry, d.title, whenText)
            DateKind.OTHER -> context.getString(R.string.date_notif_other, d.title, whenText)
        }
        return NotificationContent(title, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(occurrence), "inbox")
    }

    private suspend fun followUp(ref: String): NotificationContent? {
        val id = LifeRefs.parseFollowUp(ref) ?: return null
        val f = followUps.get(id)?.takeIf { !it.done } ?: return null
        return NotificationContent(
            context.getString(R.string.follow_notif_title, f.person),
            context.getString(R.string.follow_notif_text, f.about),
            "inbox",
            actionRef = ref,
        )
    }

    private suspend fun focus(ref: String): NotificationContent? {
        val id = LifeRefs.parseFocus(ref) ?: return null
        val s = reflection.running()?.takeIf { it.id == id } ?: return null
        // The block ran its full time: it counts as completed.
        reflection.end(s.id, time.now(), completed = true)
        return NotificationContent(
            context.getString(R.string.focus_notif_title),
            context.getString(R.string.focus_notif_text, s.title, s.plannedMinutes),
            "today",
        )
    }

    /**
     * The extra line for the morning brief: today's important dates, habit check-ins and follow-ups
     * due, or null when there is nothing. Titles only, no message text.
     */
    suspend fun briefExtras(today: java.time.LocalDate): String? {
        val parts = ArrayList<String>()
        runCatching {
            DateRules.upcoming(dates.datesNow(), today, 0).forEach { (d, _) -> parts += d.title }
        }
        val slots = runCatching { app.assistant.domain.engine.life.HabitRules.dayProgress(habits.habitsNow(), emptyList(), today).second }.getOrDefault(0)
        val follow = runCatching { followUps.openNow().count { !it.nudgeOn.isAfter(today) } }.getOrDefault(0)
        val lines = buildList {
            if (parts.isNotEmpty()) add(context.getString(R.string.brief_dates, parts.joinToString(", ")))
            if (slots > 0) add(context.resources.getQuantityString(R.plurals.brief_habits, slots, slots))
            if (follow > 0) add(context.resources.getQuantityString(R.plurals.brief_follow_ups, follow, follow))
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The "Done" button: ticks a habit slot or closes a follow-up. */
    suspend fun done(ref: String) {
        LifeRefs.parseHabit(ref)?.let { habits.setChecked(it.id, it.date, it.slot, done = true) }
        LifeRefs.parseFollowUp(ref)?.let { followUps.setDone(it, done = true) }
    }
}
