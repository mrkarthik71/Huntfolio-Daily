package app.assistant.ui.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.assistant.domain.model.EnergyTag

/** Energy colours: each differs in lightness as well as hue, and is always paired with a text label. */
object EnergyColors {
    val DeepWork = Color(0xFFE6C58F)
    val LightAdmin = Color(0xFF9CC3F5)
    val Physical = Color(0xFF7ED7AE)
    val Rest = Color(0xFFF0A58F)
    val Meeting = Color(0xFFD8D6CF)
    val Warning = Color(0xFFFFC27A)
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
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 6.dp),
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
    track: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val p = animatedProgress(progress.coerceIn(0f, 1f))
    Box(modifier.size(size).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The focus-now hero: what you should be doing, how long is left, and a few facts about the day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusHero(
    eyebrow: String,
    title: String,
    subtitle: String?,
    progress: Float?,
    facts: List<String>,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    HeroCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Eyebrow(eyebrow, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle !=
                null
            ) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
            }
            if (progress != null) ApexProgressBar(progress, accent, track = MaterialTheme.colorScheme.surfaceContainerHighest)
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
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.surface)
            .credEdge(MaterialTheme.shapes.large).height(IntrinsicSize.Min),
    ) {
        // A thin warm rail marks a heads-up without painting the whole card amber.
        Box(Modifier.width(3.dp).fillMaxHeight().background(EnergyColors.Warning))
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = EnergyColors.Warning, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (showWhy.value) Text(why, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.85f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (acceptLabel != null) PillButton(acceptLabel, onAccept)
                PillButton(dismissLabel, onDismiss, container = Color.Transparent, content = scheme.onSurface, border = scheme.outline)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showWhy.value = !showWhy.value }) { Text(whyLabel, color = scheme.primary) }
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
    container: Color = MaterialTheme.colorScheme.onBackground,
    content: Color = MaterialTheme.colorScheme.background,
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
        modifier.fillMaxWidth().pressScale(interaction).alpha(if (done) DONE_ALPHA else 1f).clip(shape)
            .background(
                if (isNow) {
                    scheme.primaryContainer
                } else if (dashed) {
                    Color.Transparent
                } else {
                    scheme.surface
                },
            )
            .let {
                when {
                    isNow -> it.border(1.dp, scheme.primary.copy(alpha = 0.45f), shape)
                    dashed -> it.border(1.dp, scheme.outlineVariant, shape)
                    else -> it.credEdge(shape)
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
            Text(start, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Text(end, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        if (energy != null) Box(Modifier.width(4.dp).height(36.dp).clip(CircleShape).background(energy))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done) {
                    scheme.onSurfaceVariant
                } else if (isNow) {
                    scheme.onPrimaryContainer
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
                color = if (isNow) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
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
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.background).padding(10.dp),
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
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)
            .credEdge(MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private const val DONE_ALPHA = 0.62f
