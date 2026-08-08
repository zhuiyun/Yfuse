package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How long the ring takes to leave the icon behind. */
private const val BURST_MS = 420

/** Turning something off is an undo, not an event: it settles rather than celebrates. */
private const val RELEASE_MS = 200

/**
 * An icon that answers being switched on.
 *
 * 收藏 and 稍后观看 used to swap one glyph for another between two frames, which is the
 * least an app can do for the two actions users press most and get nothing else back
 * from — no page changes, no navigation, just a filled heart where an outline was.
 *
 * Switching on springs the glyph up from small with a little overshoot and pushes a ring
 * out behind it; switching off eases a slight overshoot back down and draws no ring,
 * because undoing something should not look like a reward.
 *
 * The whole thing is inert under 减弱动态效果 — the glyph simply changes, as before.
 */
@Composable
fun BurstIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String?,
    tint: Color,
    burstColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    // The state the animation has already reacted to. Without it the effect fires on every
    // recomposition that re-keys, including the first — so opening an already-favourited
    // title would burst at it.
    var reacted by remember { mutableStateOf(active) }

    LaunchedEffect(active, reduceMotion) {
        if (active == reacted) return@LaunchedEffect
        val turnedOn = active && !reacted
        reacted = active
        if (reduceMotion) return@LaunchedEffect
        if (turnedOn) {
            ring.snapTo(0f)
            launch {
                ring.animateTo(1f, tween(BURST_MS, easing = LinearOutSlowInEasing))
            }
            pop.snapTo(0.6f)
            pop.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.38f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            pop.snapTo(1.16f)
            pop.animateTo(1f, tween(RELEASE_MS, easing = Motion.Curve))
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val progress = ring.value
        if (progress < 1f) {
            Canvas(Modifier.size(iconSize * 2.4f)) {
                // Starts just outside the glyph and runs to the edge of this box, thinning
                // and fading as it goes.
                val radius = size.minDimension / 2f * (0.38f + progress * 0.62f)
                drawCircle(
                    color = burstColor.copy(alpha = (1f - progress) * 0.5f),
                    radius = radius,
                    style = Stroke(width = (1f - progress) * 2.5f.dp.toPx() + 0.5f),
                )
            }
        }
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        )
    }
}
