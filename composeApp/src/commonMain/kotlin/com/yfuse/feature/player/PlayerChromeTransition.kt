package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion

/**
 * How long the player's own chrome takes to arrive or leave.
 *
 * Kept with the transition implementation so the high-frequency control surface does not
 * own animation mechanics in addition to gesture and overlay orchestration.
 */
private const val CHROME_MS = Motion.STANDARD

/**
 * Which edge a piece of player chrome belongs to, and therefore where it comes from.
 *
 * Chrome that is anchored to an edge should arrive from that edge — it is the difference
 * between a panel that slid in from where it lives and one that materialised on top of the
 * film. [None] is for surfaces that own the whole screen and have no edge of their own.
 */
internal enum class ChromeEdge { Top, Bottom, End, None }

/**
 * Entrance and exit for one piece of player chrome.
 *
 * The travel is deliberately a fraction of the surface rather than a fixed distance: the top
 * bar, the transport row and the episode drawer are wildly different heights, and a shared
 * dp would read as a nudge on one and a lurch on another. A sixth of the surface's own size
 * looks like the same gesture on all of them.
 *
 * Under 减弱动态效果 the movement goes and the crossfade stays: chrome appearing instantly
 * over a moving picture is harder to follow than chrome that fades, and a fade is not the
 * kind of motion that setting is there to suppress.
 */
@Composable
internal fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    edge: ChromeEdge = ChromeEdge.None,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val fade = tween<Float>(CHROME_MS, easing = Motion.Curve)
    val slide = tween<IntOffset>(CHROME_MS, easing = Motion.Curve)
    val travel: (Int) -> Int = { full -> full / 6 }
    val moving = !reduceMotion

    val enter =
        when {
            !moving -> fadeIn(fade)
            edge == ChromeEdge.Top -> fadeIn(fade) + slideInVertically(slide) { -travel(it) }
            edge == ChromeEdge.Bottom -> fadeIn(fade) + slideInVertically(slide) { travel(it) }
            edge == ChromeEdge.End -> fadeIn(fade) + slideInHorizontally(slide) { travel(it) }
            // Anchored inside its own full-screen box, so it grows out of the corner it sits in
            // rather than sliding the invisible dismiss catcher around with it.
            else ->
                fadeIn(fade) +
                    scaleIn(
                        tween(CHROME_MS, easing = Motion.Curve),
                        initialScale = 0.94f,
                        transformOrigin = TransformOrigin(1f, 1f),
                    )
        }
    val exit =
        when {
            !moving -> fadeOut(fade)
            edge == ChromeEdge.Top -> fadeOut(fade) + slideOutVertically(slide) { -travel(it) }
            edge == ChromeEdge.Bottom -> fadeOut(fade) + slideOutVertically(slide) { travel(it) }
            edge == ChromeEdge.End -> fadeOut(fade) + slideOutHorizontally(slide) { travel(it) }
            else ->
                fadeOut(fade) +
                    scaleOut(
                        tween(CHROME_MS, easing = Motion.Curve),
                        targetScale = 0.94f,
                        transformOrigin = TransformOrigin(1f, 1f),
                    )
        }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}
