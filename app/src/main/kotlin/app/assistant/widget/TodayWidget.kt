package app.assistant.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.assistant.domain.engine.calendar.TodayTimeline
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TimedEvent
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TimeSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/** Day/night colours via the public glance.color factory (the unit/resource overloads are restricted). */
private val OnBg = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1E5))
private val Bg = ColorProvider(day = Color(0xFFFFFBFE), night = Color(0xFF1C1B1F))
private val Muted = ColorProvider(day = Color(0xFF6B6B70), night = Color(0xFFA8A8AD))

/** Hilt entry point for the widget and its action callbacks (Glance components are not @AndroidEntryPoint). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun plans(): PlanRepository
    fun calendar(): CalendarRepository
    fun tasks(): TaskRepository
    fun time(): TimeSource
}

internal fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/**
 * Home-screen widget (design: Today_Widget). It shows today's priorities, lets the User tick one
 * done in place, offers a Quick_Add shortcut, and taps through to the app on Today. It holds no
 * user data of its own and reads through the encrypted store like every other reader.
 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = widgetEntryPoint(context)
        val today = ep.time().today()
        val priorities = runCatching {
            ep.plans().ensurePlanFor(today).priorities.map {
                WidgetModel(taskId = it.id.value, title = it.title, done = it.status == app.assistant.domain.model.TaskStatus.DONE)
            }
        }.getOrDefault(emptyList())
        val nextEvent = runCatching { nextEventText(ep, today) }.getOrNull()
        val openToday = Intent(context, app.assistant.ui.MainActivity::class.java)
            .putExtra("route", "today")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openTasks = Intent(context, app.assistant.ui.MainActivity::class.java)
            .putExtra("route", "tasks")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        provideContent { WidgetContent(priorities, nextEvent, openToday, openTasks) }
    }

    /** The next timed event today as "HH:mm Title", or null when none is left (Requirement 21.5). */
    private suspend fun nextEventText(ep: WidgetEntryPoint, today: java.time.LocalDate): String? {
        val zone = ep.time().zone()
        val (_, dayEnd) = TodayTimeline.dayWindow(today, zone)
        val events = ep.calendar().eventsOn(today).first().timed.mapNotNull { f -> f.timed?.let { TimedEvent(it, f.title) } }
        val next = TodayTimeline.nextEvent(events, ep.time().now(), dayEnd) ?: return null
        return "${next.range.start.atZone(zone).toLocalTime().withSecond(0).withNano(0)}  ${next.title}"
    }

    @androidx.compose.runtime.Composable
    private fun WidgetContent(priorities: List<WidgetModel>, nextEvent: String?, openToday: Intent, openTasks: Intent) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Bg).padding(12.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openToday))) {
                Text("Today", style = TextStyle(color = OnBg, fontWeight = FontWeight.Bold))
            }
            Spacer(modifier = GlanceModifier.height(6.dp))
            if (nextEvent != null) {
                Text(nextEvent, style = TextStyle(color = Muted))
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
            if (priorities.isEmpty()) {
                Text("No priorities set", style = TextStyle(color = Muted))
            } else {
                priorities.take(3).forEach { model ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clickable(
                                actionRunCallback<CompleteTaskAction>(
                                    actionParametersOf(CompleteTaskAction.TASK_ID to model.taskId),
                                ),
                            ),
                    ) {
                        val mark = if (model.done) "\u2713" else "\u25CB"
                        Text("$mark  ${model.title}", style = TextStyle(color = OnBg))
                    }
                }
            }
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openTasks))) {
                Text("+ Quick add", style = TextStyle(color = OnBg, fontWeight = FontWeight.Medium))
            }
        }
    }
}

/** Ticking a priority marks it done in the encrypted store, then redraws every widget (Requirement 14, 11.5). */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TASK_ID] ?: return
        val ep = widgetEntryPoint(context)
        runCatching { ep.tasks().markDone(TaskId(taskId), at = ep.time().now(), on = ep.time().today()) }
        TodayWidget().updateAll(context)
    }

    companion object {
        val TASK_ID = ActionParameters.Key<Long>("task_id")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
