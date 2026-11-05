package app.assistant.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.ask.AskAnswer
import app.assistant.domain.engine.ask.AskEngine
import app.assistant.domain.engine.ask.AskPeriod
import app.assistant.domain.engine.ask.Tip
import app.assistant.domain.engine.ask.TipKind
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Paise
import app.assistant.ui.common.formatShortDate
import app.assistant.ui.designsystem.ApexProgressBar
import app.assistant.ui.designsystem.EnergyColors
import app.assistant.ui.designsystem.Eyebrow
import app.assistant.ui.designsystem.ProgressRing
import app.assistant.ui.designsystem.animatedProgress
import app.assistant.ui.designsystem.credEdge
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.pressScale
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Suggestion(val labelRes: Int, val askRes: Int = labelRes, val prefillOnly: Boolean = false)

private val GROUPS = listOf(
    R.string.ask_group_money to listOf(
        Suggestion(R.string.ask_s_spend_month),
        Suggestion(R.string.ask_s_food_week),
        Suggestion(R.string.ask_s_save),
        Suggestion(R.string.ask_s_subs),
        Suggestion(R.string.ask_s_bills),
        Suggestion(R.string.ask_s_last_month),
    ),
    R.string.ask_group_plan to listOf(Suggestion(R.string.ask_s_goals), Suggestion(R.string.ask_s_today)),
    R.string.ask_group_memory to listOf(
        Suggestion(R.string.ask_s_remember_label, R.string.ask_s_remember, prefillOnly = true),
        Suggestion(R.string.ask_s_remind),
        Suggestion(R.string.ask_s_recall),
    ),
)

/** Ask Apex: a CRED-style conversation over the User's own data, rule answers first. */
@Composable
fun AskScreen(onBack: () -> Unit, initialQuestion: String? = null, viewModel: AskViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AskContent(ui, onBack = onBack, onAsk = viewModel::ask, onClear = viewModel::clear)
    LaunchedEffect(initialQuestion) { if (!initialQuestion.isNullOrBlank()) viewModel.ask(initialQuestion) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskContent(ui: AskUi, onBack: () -> Unit, onAsk: (String) -> Unit, onClear: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ui.turns.size, ui.turns.lastOrNull()?.ai) { if (ui.turns.isNotEmpty()) list.animateScrollToItem(ui.turns.size) }
    val send = {
        val q = draft
        draft = ""
        onAsk(q)
    }
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ask_back), tint = scheme.onSurface) }
            Spacer(Modifier.size(12.dp))
            Eyebrow(stringResource(R.string.ask_eyebrow), color = scheme.primary, modifier = Modifier.weight(1f))
            if (ui.turns.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.ask_clear), color = scheme.onSurfaceVariant) }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "intro") {
                Column(Modifier.enterAnimated(0), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ask_title),
                        style = MaterialTheme.typography.displayMedium,
                        color = scheme.onBackground,
                        modifier = Modifier.semantics {
                            heading()
                        },
                    )
                    Text(stringResource(R.string.ask_subtitle), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    if (ui.turns.isEmpty()) {
                        var index = 1
                        GROUPS.forEach { (title, items) ->
                            Spacer(Modifier.size(8.dp))
                            Eyebrow(stringResource(title))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items.forEach { s ->
                                    val ask = stringResource(s.askRes)
                                    SuggestionChip(stringResource(s.labelRes), Modifier.enterAnimated(index++)) {
                                        if (s.prefillOnly) draft = ask else onAsk(ask)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(ui.turns, key = { it.id }) { turn ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuestionBubble(turn.question, Modifier.enterAnimated(0))
                    AnswerCard(turn, Modifier.enterAnimated(1)) { onAsk(it) }
                }
            }
        }
        Composer(draft, onChange = { draft = it }, onSend = send, enabled = !ui.busy)
    }
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(interaction).clip(CircleShape).background(MaterialTheme.colorScheme.surface).credEdge(CircleShape)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SuggestionChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.pressScale(interaction).heightIn(min = 44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).credEdge(CircleShape)
            .clickable(interaction, ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun QuestionBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                .background(MaterialTheme.colorScheme.onBackground).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Composer(text: String, onChange: (String) -> Unit, onSend: () -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val canSend = enabled && text.isNotBlank()
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(CircleShape).background(scheme.surfaceContainerHigh).credEdge(CircleShape).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (text.isEmpty()) Text(stringResource(R.string.ask_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(44.dp).pressScale(interaction).clip(CircleShape).background(if (canSend) scheme.onBackground else scheme.surfaceVariant)
                .clickable(interaction, ripple(), enabled = canSend, role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ask_send), tint = if (canSend) scheme.background else scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnswerCard(turn: AskTurn, modifier: Modifier = Modifier, onAsk: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.surface).credEdge(MaterialTheme.shapes.large).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val a = turn.answer) {
            is AskAnswer.Spend -> SpendAnswer(a)
            is AskAnswer.Subscriptions -> SubscriptionsAnswer(a)
            is AskAnswer.Bills -> BillsAnswer(a)
            is AskAnswer.Goals -> GoalsAnswer(a)
            is AskAnswer.Today -> TodayAnswer(a)
            is AskAnswer.Tips -> TipsAnswer(a.tips)
            is AskAnswer.Remember -> if (turn.saved) {
                SavedHeader(stringResource(R.string.ask_saved_eyebrow))
                Text(a.text, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            } else {
                Body(stringResource(R.string.ask_remember_empty))
            }
            is AskAnswer.Recall -> RecallAnswer(a)
            is AskAnswer.Remind -> if (turn.saved && a.at != null) {
                SavedHeader(stringResource(R.string.ask_remind_eyebrow))
                Text(a.text, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                Text(a.at.format(REMIND_FORMAT), style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
            } else {
                Body(stringResource(R.string.ask_remind_need_time))
            }
            is AskAnswer.Unknown -> AiAnswer(turn.ai, onAsk)
        }
    }
}

private val REMIND_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.getDefault())

@Composable
private fun SavedHeader(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(EnergyColors.Physical), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(14.dp))
        }
        Eyebrow(text, color = EnergyColors.Physical)
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)

/** A rupee amount that counts up from zero the first time it is shown. */
@Composable
fun CountUpAmount(amount: Paise, modifier: Modifier = Modifier) {
    val p = animatedProgress(1f)
    Text(
        MoneyFormatter.format(Paise((amount.value * p).toLong()), MoneyFormatter.Paisa.WHEN_NONZERO),
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
private fun periodLabel(p: AskPeriod): String = stringResource(
    when (p) {
        AskPeriod.TODAY -> R.string.ask_period_today
        AskPeriod.WEEK -> R.string.ask_period_week
        AskPeriod.MONTH -> R.string.ask_period_month
        AskPeriod.LAST_MONTH -> R.string.ask_period_last_month
        AskPeriod.YEAR -> R.string.ask_period_year
    },
)

@Composable
private fun SpendAnswer(a: AskAnswer.Spend) {
    val scheme = MaterialTheme.colorScheme
    val period = periodLabel(a.period)
    Eyebrow(
        a.category?.let {
            stringResource(R.string.ask_spent_on, AskEngine.categoryName(it).uppercase(Locale.getDefault()), period)
        } ?: period,
        color = scheme.primary,
    )
    if (a.count == 0) {
        Body(stringResource(R.string.ask_no_spends))
        return
    }
    CountUpAmount(a.total)
    Text(stringResource(R.string.ask_payments, a.count), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    a.previous?.let { prev ->
        val pct = if (prev.value == 0L) null else ((a.total.value - prev.value) * HUNDRED / prev.value).toInt()
        val (text, color) = when {
            pct == null || pct == 0 -> stringResource(R.string.ask_level_vs) to scheme.onSurfaceVariant
            pct > 0 -> stringResource(R.string.ask_up_vs, pct, MoneyFormatter.format(prev)) to EnergyColors.Rest
            else -> stringResource(R.string.ask_down_vs, -pct, MoneyFormatter.format(prev)) to EnergyColors.Physical
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
    if (a.category == null && a.byCategory.isNotEmpty()) {
        Eyebrow(stringResource(R.string.ask_by_category))
        val max = a.byCategory.maxOf { it.amount.value }.coerceAtLeast(1)
        a.byCategory.forEachIndexed { i, row ->
            BarRow(row.label, MoneyFormatter.format(row.amount), row.amount.value.toFloat() / max, Modifier.enterAnimated(i + 2))
        }
    }
    if (a.topMerchants.isNotEmpty()) {
        Eyebrow(stringResource(R.string.ask_top_merchants))
        a.topMerchants.forEach { ValueRow(it.label, MoneyFormatter.format(it.amount), null) }
    }
}

@Composable
private fun BarRow(label: String, value: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        ApexProgressBar(animatedProgress(fraction), MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ValueRow(title: String, value: String, supporting: String?, supportingColor: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = supportingColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SubscriptionsAnswer(a: AskAnswer.Subscriptions) {
    Eyebrow(stringResource(R.string.ask_subs_eyebrow, a.items.size), color = MaterialTheme.colorScheme.primary)
    if (a.items.isEmpty()) {
        Body(stringResource(R.string.ask_no_subs))
        return
    }
    CountUpAmount(a.monthly)
    Text(
        stringResource(R.string.ask_per_year, MoneyFormatter.format(a.yearly)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    a.items.forEach { s ->
        ValueRow(
            s.name,
            stringResource(R.string.ask_per_month, MoneyFormatter.format(s.monthly)),
            if (s.unusedFlag) stringResource(R.string.ask_check_usage) else stringResource(R.string.ask_renews, formatShortDate(s.nextDue)),
            if (s.unusedFlag) EnergyColors.Warning else null,
        )
    }
}

@Composable
private fun BillsAnswer(a: AskAnswer.Bills) {
    Eyebrow(stringResource(R.string.ask_bills_eyebrow, a.items.size), color = MaterialTheme.colorScheme.primary)
    if (a.items.isEmpty()) {
        Body(stringResource(R.string.ask_no_bills))
        return
    }
    CountUpAmount(a.total)
    a.items.forEach { ValueRow(it.name, MoneyFormatter.format(it.amount), stringResource(R.string.ask_due_on, formatShortDate(it.dueDate))) }
}

@Composable
private fun GoalsAnswer(a: AskAnswer.Goals) {
    Eyebrow(stringResource(R.string.ask_goals_eyebrow, a.items.size), color = MaterialTheme.colorScheme.primary)
    if (a.items.isEmpty()) {
        Body(stringResource(R.string.ask_no_goals))
        return
    }
    a.items.forEach { g ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(
                g.percent / HUNDRED_F,
                "${g.percent}%",
                size = 52.dp,
                color = if (g.daysBehind >
                    0
                ) {
                    EnergyColors.Warning
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Column(Modifier.weight(1f)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (g.daysBehind > 0) stringResource(R.string.ask_goal_behind, g.daysBehind) else stringResource(R.string.ask_goal_on_track),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (g.daysBehind > 0) EnergyColors.Warning else EnergyColors.Physical,
                )
            }
        }
    }
}

@Composable
private fun TodayAnswer(a: AskAnswer.Today) {
    Eyebrow(stringResource(R.string.ask_today_eyebrow), color = MaterialTheme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ProgressRing(if (a.total == 0) 0f else a.done.toFloat() / a.total, "${a.done}/${a.total}", size = 60.dp)
        Text(
            stringResource(R.string.ask_today_done, a.done, a.total),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (a.open.isEmpty()) {
        Body(stringResource(R.string.ask_today_clear))
    } else {
        Eyebrow(stringResource(R.string.ask_today_next))
        a.open.take(MAX_LIST).forEachIndexed { i, t -> NumberedLine(i + 1, t) }
    }
}

@Composable
private fun NumberedLine(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TipsAnswer(tips: List<Tip>) {
    Eyebrow(stringResource(R.string.ask_tips_eyebrow), color = MaterialTheme.colorScheme.primary)
    tips.forEachIndexed { i, t -> Box(Modifier.enterAnimated(i + 1)) { NumberedLine(i + 1, tipText(t)) } }
}

@Composable
fun tipText(t: Tip): String {
    val amt = MoneyFormatter.format(t.amount)
    return when (t.kind) {
        TipKind.CATEGORY_UP -> stringResource(R.string.ask_tip_category_up, t.subject, amt, MoneyFormatter.format(t.other))
        TipKind.SUBSCRIPTIONS_YEARLY -> stringResource(R.string.ask_tip_subs_yearly, t.count, amt)
        TipKind.UNUSED_SUBSCRIPTION -> stringResource(R.string.ask_tip_unused, t.subject, amt)
        TipKind.TOP_MERCHANT -> stringResource(R.string.ask_tip_top_merchant, t.subject, amt, t.count)
        TipKind.SMALL_LEAKS -> stringResource(R.string.ask_tip_small_leaks, t.count, amt)
        TipKind.GOAL_BEHIND -> stringResource(R.string.ask_tip_goal_behind, t.subject, t.count)
        TipKind.BILLS_SOON -> stringResource(R.string.ask_tip_bills_soon, t.count, amt)
        TipKind.ALL_GOOD -> stringResource(R.string.ask_tip_all_good)
    }
}

@Composable
private fun RecallAnswer(a: AskAnswer.Recall) {
    Eyebrow(stringResource(R.string.ask_recall_eyebrow, a.notes.size), color = MaterialTheme.colorScheme.primary)
    if (a.notes.isEmpty()) {
        Body(stringResource(R.string.ask_recall_none))
        return
    }
    a.notes.take(MAX_LIST).forEach { ValueRow(it.text, "", formatShortDate(it.createdOn)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiAnswer(ai: AiPart, onAsk: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Eyebrow(stringResource(R.string.ask_ai_eyebrow), color = scheme.primary)
    when (ai) {
        AiPart.Thinking, AiPart.None -> Text(
            stringResource(R.string.ask_ai_thinking),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        is AiPart.Wording -> {
            ai.summary?.let { Body(it) }
            ai.suggestions.forEachIndexed { i, s -> NumberedLine(i + 1, s) }
        }
        AiPart.Unavailable -> {
            Body(stringResource(R.string.ask_ai_unavailable))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.ask_s_spend_month, R.string.ask_s_save, R.string.ask_s_goals).forEach { res ->
                    val q = stringResource(res)
                    SuggestionChip(q) { onAsk(q) }
                }
            }
        }
    }
}

private const val HUNDRED = 100
private const val HUNDRED_F = 100f
private const val MAX_LIST = 6

/** The Ask Apex entry on Today: a raised capsule with a gold spark, tapping it opens the conversation. */
@Composable
fun AskEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().pressScale(interaction).clip(CircleShape).background(scheme.surface).credEdge(CircleShape)
            .clickable(interaction, ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ask_entry), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Text(stringResource(R.string.ask_entry_sub), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
