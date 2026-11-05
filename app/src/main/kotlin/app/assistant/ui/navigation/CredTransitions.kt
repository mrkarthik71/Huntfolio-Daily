package app.assistant.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * CRED-style navigation motion (see CLAUDE.md, Design language):
 * - a pushed screen slides in from the right on a soft spring while its parent recedes (slight
 *   scale-down, fade) and drifts left; back reverses it, a little quicker;
 * - switching bottom-bar tabs is a fade-through with a small scale, so tabs feel like layers, not pages;
 * - with reduced motion every change is a plain short fade.
 */
internal class CredTransitions(private val tabRoutes: Set<String>, private val reduced: Boolean) {
    private val slide = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    private val fastFade = tween<Float>(EXIT_MS, easing = FastOutLinearInEasing)
    private val slowFade = tween<Float>(ENTER_MS, delayMillis = THROUGH_DELAY_MS, easing = LinearOutSlowInEasing)

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
        initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition = when {
        reduced -> fadeIn(tween(PLAIN_MS))
        scope.isTabSwitch() -> fadeIn(slowFade) + scaleIn(slowFade, initialScale = TAB_SCALE)
        else -> slideInHorizontally(slide) { it } + fadeIn(tween(ENTER_MS))
    }

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition = when {
        reduced -> fadeOut(tween(PLAIN_MS))
        scope.isTabSwitch() -> fadeOut(fastFade)
        else -> slideOutHorizontally(slide) { -it / PARALLAX } + scaleOut(tween(ENTER_MS), targetScale = RECEDE_SCALE) +
            fadeOut(tween(ENTER_MS), targetAlpha = RECEDE_ALPHA)
    }

    fun popEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition = when {
        reduced -> fadeIn(tween(PLAIN_MS))
        scope.isTabSwitch() -> fadeIn(slowFade) + scaleIn(slowFade, initialScale = TAB_SCALE)
        else -> slideInHorizontally(slide) { -it / PARALLAX } + scaleIn(tween(EXIT_MS), initialScale = RECEDE_SCALE) +
            fadeIn(tween(EXIT_MS), initialAlpha = RECEDE_ALPHA)
    }

    fun popExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition = when {
        reduced -> fadeOut(tween(PLAIN_MS))
        scope.isTabSwitch() -> fadeOut(fastFade)
        else -> slideOutHorizontally(tween(EXIT_MS, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(EXIT_MS))
    }

    private companion object {
        const val ENTER_MS = 320
        const val EXIT_MS = 200
        const val PLAIN_MS = 150
        const val THROUGH_DELAY_MS = 90
        const val PARALLAX = 4
        const val TAB_SCALE = 0.96f
        const val RECEDE_SCALE = 0.94f
        const val RECEDE_ALPHA = 0.4f
    }
}
