package app.assistant.ui.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.assistant.domain.model.EnergyTag

/** Energy colours: each differs in lightness as well as hue, and is always paired with a text label. */
object EnergyColors {
    val DeepWork = Color(0xFFFF8A3D)
    val LightAdmin = Color(0xFF8FC1FF)
    val Physical = Color(0xFF7FD8A6)
    val Rest = Color(0xFFE9C98F)
    val Meeting = Color(0xFFD8D5CD)
    val Warning = Color(0xFFFF8A3D)
    val WarningSurface = Color(0xFF1E1A14)
    val WarningOutline = Color(0xFF4A3A1E)
    val WarningText = Color(0xFFE8D9C4)

    fun of(tag: EnergyTag?): Color = when (tag) {
        EnergyTag.DEEP_WORK -> DeepWork
        EnergyTag.LIGHT_ADMIN -> LightAdmin
        EnergyTag.PHYSICAL_ROUTINE -> Physical
        EnergyTag.REST -> Rest
        null -> Meeting
    }
}

/** A small uppercase caption with letter-spacing, the Cred section voice. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

/** A rounded chip on the ink canvas, used inside hero cards for small facts. */
@Composable
fun FactChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.recessedWell(CircleShape, deep = true).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A thin rounded bar that fills to [progress] (0..1) with a count-up animation. */
@Composable
fun ApexProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.outlineVariant, height: Dp = 6.dp) {
    val p = animatedProgress(progress.coerceIn(0f, 1f))
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(CircleShape).background(color))
    }
}

/** A progress ring that draws itself in on first show, with the percent in its centre. */
@Composable
fun ProgressRing(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = AssistantColors.Hairline,
) {
    Dial(progress, label, caption = null, modifier = modifier, size = size, color = color, description = label, track = track)
}

/**
 * The focus-now hero (Instrument panel): the day's progress dial on the left; on the right the live
 * caption, what you should be doing and the time left in mono. Facts sit below in small wells.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList") // every slot is a distinct readout on the hero
fun FocusHero(
    eyebrow: String,
    title: String,
    subtitle: String?,
    progress: Float?,
    facts: List<String>,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    dayDone: Int = 0,
    dayTotal: Int = 0,
    dayCaption: String? = null,
    live: Boolean = progress != null,
) {
    HeroCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (dayTotal > 0) {
                    Dial(
                        dayDone.toFloat() / dayTotal,
                        "$dayDone/$dayTotal",
                        dayCaption,
                        description =
                        dayCaption?.let { "$dayDone/$dayTotal $it" } ?: "$dayDone/$dayTotal",
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Caption(eyebrow, color = accent, live = live)
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (progress != null) ApexProgressBar(progress, accent, track = AssistantColors.Hairline, height = 4.dp)
            if (facts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { facts.forEach { FactChip(it) } }
            }
        }
    }
}

/** A heads-up card from the proactive agent, with its reason one tap away and nothing applied until accepted. */
@Composable
fun ProactiveCardView(
    title: String,
    body: String,
    acceptLabel: String?,
    dismissLabel: String,
    whyLabel: String,
    why: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showWhy = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    // A heads-up is an indicator lamp, not a painted card: one glowing row, the detail one tap away.
    val open = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(scheme.primary.copy(alpha = 0.08f))
            .border(1.dp, scheme.primary.copy(alpha = 0.25f), MaterialTheme.shapes.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open.value = !open.value }.heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveLight(scheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (acceptLabel != null) TextButton(onClick = onAccept) { Text(acceptLabel, color = scheme.primary, style = MaterialTheme.typography.labelLarge) }
        }
        if (open.value) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                if (showWhy.value) Text(why, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel, color = scheme.onSurface) }
                    TextButton(onClick = { showWhy.value = !showWhy.value }) { Text(whyLabel, color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** A compact pill button with a press-scale. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    border: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(interaction).heightIn(min = 44.dp).clip(CircleShape).background(container)
            .let { if (border != null) it.border(BorderStroke(1.dp, border), CircleShape) else it }
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = content) }
}

/**
 * One timeline row: start/end times, an energy stripe, the title with its kind, and a trailing
 * slot. [isNow] highlights the row with the accent container; [dashed] draws a free slot.
 */
@Composable
fun TimelineRow(
    start: String,
    end: String,
    title: String,
    subtitle: String,
    energy: Color?,
    isNow: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().pressScale(interaction).alpha(if (done) DONE_ALPHA else 1f)
            .let {
                // Rows sit on the console ground; only the live one is pressed into a well.
                when {
                    isNow -> it.recessedWell(shape).border(1.dp, scheme.primary.copy(alpha = 0.35f), shape)
                    dashed -> it.clip(shape).border(1.dp, scheme.outlineVariant, shape)
                    else -> it.clip(shape)
                }
            }
            .let {
                if (onClick !=
                    null
                ) {
                    it.clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
                } else {
                    it
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.widthIn(min = 44.dp)) {
            Text(
                start,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Text(end, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        if (energy != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(energy))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done) {
                    scheme.onSurfaceVariant
                } else if (isNow) {
                    scheme.onBackground
                } else {
                    scheme.onSurface
                },
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isNow) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = EnergyColors.Physical, modifier = Modifier.size(20.dp))
        trailing?.invoke()
    }
}

/** A stat block for hero rows: caption over a number. */
@Composable
fun MiniStat(caption: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.recessedWell(MaterialTheme.shapes.small).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Groups content in a raised card with the standard Apex padding. */
@Composable
fun ApexCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private const val DONE_ALPHA = 0.62f
