package app.assistant.ui.designsystem

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The motion system. Rules (ui-ux-pro-max: motion conveys meaning, 1–2 key moving elements per view,
 * exits faster than entrances, respect the system's reduced-motion setting):
 * - entrances decelerate ([Enter], 320 ms) and lists stagger by [STAGGER_MS] with a soft spring;
 * - presses scale to 0.97 with a quick spring, so every tap gets instant feedback;
 * - numbers and rings animate from 0 to their value once, on first show;
 * - with "Remove animations" on (animator scale 0) every effect jumps straight to its end state.
 */
object Motion {
    const val ENTER_MS = 320
    const val EXIT_MS = 200
    const val STAGGER_MS = 45
    const val MAX_STAGGERED = 8
    const val COUNT_UP_MS = 700
    val Enter = tween<Float>(ENTER_MS, easing = FastOutSlowInEasing)
    val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)
    val SettleSpring = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
}

/** True when the user turned animations off in system settings. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun ProvideMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

/** Fades and lifts an item in on first composition; [index] staggers list items (capped so long lists stay quick). */
fun Modifier.enterAnimated(index: Int = 0): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            kotlinx.coroutines.delay((index.coerceAtMost(Motion.MAX_STAGGERED) * Motion.STAGGER_MS).toLong())
            progress.animateTo(1f, Motion.SettleSpring)
        }
    }
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * lift
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}

/** Scales the element down slightly while pressed (pass the same [interaction] to its clickable). */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, Motion.PressSpring, label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A value that animates from 0 to [target] the first time it is shown, and to each new target after. */
@Composable
fun animatedProgress(target: Float, durationMs: Int = Motion.COUNT_UP_MS): Float {
    val reduced = LocalReducedMotion.current
    val anim = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) anim.snapTo(target) else anim.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return anim.value
}

/**
 * The hero's slow diagonal sheen: a faint light band that drifts across once every few seconds.
 * The one ambient motion in the app; it stops entirely under reduced motion.
 */
@Composable
fun heroSheen(base: Color, highlight: Color): Brush {
    if (LocalReducedMotion.current) return Brush.linearGradient(listOf(highlight, base))
    val t by rememberInfiniteTransition(label = "sheen").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(SHEEN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sheenPos",
    )
    val span = SHEEN_SPAN
    return Brush.linearGradient(
        0f to highlight,
        (t - 0.15f).coerceIn(0f, 1f) to base,
        t.coerceIn(0f, 1f) to base.copy(alpha = 1f).compositeOverLight(),
        (t + 0.15f).coerceIn(0f, 1f) to base,
        1f to base,
        start = Offset.Zero,
        end = Offset(span, span),
    )
}

private const val SHEEN_MS = 5_200
private const val SHEEN_SPAN = 1_400f

private fun Color.compositeOverLight(): Color = Color(
    red = red + (1f - red) * 0.06f,
    green = green + (1f - green) * 0.06f,
    blue = blue + (1f - blue) * 0.06f,
    alpha = alpha,
)
