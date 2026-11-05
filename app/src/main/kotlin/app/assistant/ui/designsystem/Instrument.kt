package app.assistant.ui.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The "Instrument panel" primitives (design direction B). Every screen is built from three
// materials: the console ground, raised panels that catch light on their top edge, and recessed
// wells that hold readouts. Keep to these; do not invent other surfaces.

private val PanelGradient = Brush.verticalGradient(listOf(AssistantColors.PanelTop, AssistantColors.PanelBottom))
private val TopLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.0f)))

/** The console ground: graphite with a soft glow from the top of the screen. */
fun Modifier.consoleGround(): Modifier = background(AssistantColors.Ground).drawWithContent {
    drawRect(
        Brush.radialGradient(
            listOf(AssistantColors.GroundGlow, Color.Transparent),
            center = Offset(size.width / 2, 0f),
            radius = size.width * GLOW_SCALE,
        ),
    )
    drawContent()
}

/** A raised panel: soft drop shadow, a top-lit gradient face and a hairline of light along its top edge. */
fun Modifier.raisedPanel(shape: Shape, elevation: Dp = 14.dp): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(PanelGradient)
    .border(1.dp, TopLight, shape)

/** A recessed well: darker than the ground, shaded along its top inner edge, lit along its bottom lip. */
fun Modifier.recessedWell(shape: Shape, deep: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (deep) AssistantColors.WellDeep else AssistantColors.Well)
    .drawWithContent {
        drawContent()
        val band = INNER_SHADE_DP.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = 0f, endY = band))
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.05f))), shape)

/** The live indicator: a small signal light with a glow that breathes (still under reduced motion). */
@Composable
fun LiveLight(color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val glow = if (reduced) {
        1f
    } else {
        val t by rememberInfiniteTransition(label = "live").animateFloat(
            0.45f,
            1f,
            infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
            label = "glow",
        )
        t
    }
    Canvas(modifier.size(14.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.55f * glow), Color.Transparent), c, size.minDimension / 2),
            radius = size.minDimension / 2,
            center = c,
        )
        drawCircle(color, radius = 3.5.dp.toPx(), center = c)
    }
}

/** An uppercase mono caption, optionally led by the live light. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, live: Boolean = false) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (live) LiveLight(color)
        Text(text.uppercase(), style = AssistantType.Caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The progress dial: a signal arc on a dark track around a recessed face with a mono readout.
 * It sweeps in from zero on first show.
 */
@Composable
fun Dial(
    progress: Float,
    readout: String,
    caption: String?,
    modifier: Modifier = Modifier,
    size: Dp = 116.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    description: String = readout,
    track: Color = AssistantColors.Hairline,
) {
    val p = animatedProgress(progress.coerceIn(0f, 1f))
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = size.toPx() * RING_FRACTION
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, FULL_TURN, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(color, START_ANGLE, FULL_TURN * p, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        Column(
            Modifier.size(size * FACE_FRACTION).recessedWell(CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val big = size >= DIAL_BIG
            Text(
                readout,
                style = if (big) AssistantType.Readout else MaterialTheme.typography.labelMedium.copy(fontFamily = AssistantType.Mono),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            if (caption != null &&
                big
            ) {
                Text(
                    caption.uppercase(),
                    style = AssistantType.Caption.copy(fontSize = DIAL_CAPTION_SP),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A readout tile: a recessed well with a caption, a big mono value and an optional coloured note. */
@Composable
fun ReadoutTile(caption: String, value: String, modifier: Modifier = Modifier, note: String? = null, noteColor: Color = MaterialTheme.colorScheme.secondary) {
    Column(
        modifier.recessedWell(MaterialTheme.shapes.medium).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Caption(caption)
        Text(
            value,
            style = AssistantType.Readout.copy(fontSize = TILE_SP, lineHeight = TILE_LINE_SP),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = noteColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A square raised key with an icon (48dp target). */
@Composable
fun KeyButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onBackground) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.size(48.dp).pressScale(interaction).raisedPanel(MaterialTheme.shapes.small, elevation = 8.dp)
            .clickable(interaction, ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** The signal button: a round, glowing orange key for the one main action (Ask Apex in the bar). */
@Composable
fun SignalButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.size(size).pressScale(interaction)
            .shadow(16.dp, CircleShape, ambientColor = AssistantColors.Signal, spotColor = AssistantColors.Signal)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(AssistantColors.SignalHot, AssistantColors.Signal), center = Offset.Unspecified))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent)), CircleShape)
            .clickable(interaction, ripple(), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = AssistantColors.OnSignal, modifier = Modifier.size(size * ICON_FRACTION)) }
}

/** A plain readout row on the ground: mono time, a small square marker, title, optional trailing value. */
@Composable
fun ReadoutRow(time: String, title: String, modifier: Modifier = Modifier, marker: Color? = null, trailing: String? = null, muted: Boolean = false) {
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(width = 48.dp, height = 18.dp),
        )
        if (marker != null) Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(marker))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing !=
            null
        ) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

private const val GLOW_SCALE = 1.1f
private const val INNER_SHADE_DP = 6
private const val BREATH_MS = 1_400
private const val RING_FRACTION = 0.095f
private const val FACE_FRACTION = 0.81f
private const val FULL_TURN = 360f
private const val START_ANGLE = -90f
private const val ICON_FRACTION = 0.4f
private val DIAL_BIG = 96.dp
private val DIAL_CAPTION_SP = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
private val TILE_SP = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
private val TILE_LINE_SP = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)
