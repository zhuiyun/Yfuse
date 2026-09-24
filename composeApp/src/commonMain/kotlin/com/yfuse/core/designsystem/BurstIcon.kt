package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import com.yfuse.core.designsystem.ThemeIcon as Icon

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
    // 静息 marks the change without the burst: the icon swaps, nothing pops or rings.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val light = rememberLightFeedback()
    val visible = LocalRouteVisible.current
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    // The state the animation has already reacted to. Without it the effect fires on every
    // recomposition that re-keys, including the first — so opening an already-favourited
    // title would burst at it.
    var reacted by remember { mutableStateOf(active) }

    LaunchedEffect(active, reduceMotion, visible, light) {
        if (reduceMotion || !visible) {
            reacted = active
            pop.snapTo(1f)
            ring.snapTo(1f)
            return@LaunchedEffect
        }
        if (active == reacted) {
            // A rapid visibility/accessibility reversal may cancel the reset effect itself.
            pop.snapTo(1f)
            ring.snapTo(1f)
            return@LaunchedEffect
        }
        val turnedOn = active && !reacted
        reacted = active
        if (turnedOn) {
            light.emit(LightEffect.Converge)
            if (light.enabled) {
                ring.snapTo(1f)
            } else {
                ring.snapTo(0f)
                launch {
                    ring.animateTo(1f, tween(Motion.BURST, easing = LinearOutSlowInEasing))
                }
            }
            // A kick, not a jump: one dip and one small rebound (about 1.05) from wherever the icon
            // already is. It used to snap to 0.6 on every tap — rapid taps visibly jumped — and a
            // ζ0.38 spring swung it past 1.1 and back three times.
            pop.animateTo(1f, Motion.burst(), initialVelocity = -BURST_KICK)
        } else {
            ring.snapTo(1f)
            // Turning something off is an undo, not an event: it settles rather than celebrates.
            pop.animateTo(1f, Motion.burstRelease(), initialVelocity = BURST_RELEASE_KICK)
        }
    }

    Box(modifier.lightFeedback(light), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val progress = ring.value
            if (!light.enabled && !reduceMotion && visible && progress < 1f) {
                // Draw outside the fixed icon bounds without enlarging its layout or hit target.
                val radius = iconSize.toPx() * 1.2f * (0.38f + progress * 0.62f)
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
            modifier =
                Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    },
        )
    }
}

/** Scale units per second the icon is kicked by when it turns on (a dip to about 0.68). */
private const val BURST_KICK = 12f

/** The smaller outward kick of turning off (a swell to about 1.08). */
private const val BURST_RELEASE_KICK = 8f
