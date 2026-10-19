package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Observes an activation; never postpones the action until the animation finishes. */
@Composable
fun Modifier.cinemaActivationPulse(
    trigger: Any?,
    color: Color,
    orbit: Boolean = false,
    enabled: Boolean = true,
): Modifier {
    val moving = cinemaMotion() && enabled
    val progress = remember { Animatable(1f) }
    var observed by remember { mutableStateOf(trigger) }
    LaunchedEffect(trigger, moving) {
        val changed = observed != trigger
        observed = trigger
        if (!moving || !changed) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if (orbit) CinemaMotion.CONFIRM else CinemaMotion.PLAY, easing = LinearEasing))
        }
    }
    if (!moving) return this
    return graphicsLayer {
        val scale = if (moving && orbit) cinemaConfirmationScale(progress.value) else 1f
        scaleX = scale
        scaleY = scale
    }.drawWithContent {
        drawContent()
        if (moving && progress.value < 1f) {
            if (orbit) {
                drawCinemaOrbit(progress.value, color, size.minDimension * 0.8f)
            } else {
                drawCircle(
                    color = color.copy(alpha = (1f - progress.value) * 0.5f),
                    radius = size.minDimension * (0.25f + progress.value * 0.8f),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
fun Modifier.cinemaConfirmation(
    active: Boolean,
    color: Color,
): Modifier {
    var previous by remember { mutableStateOf(active) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active && !previous) generation++
        previous = active
    }
    return cinemaActivationPulse(generation, color, orbit = true, enabled = active)
}

internal fun DrawScope.drawCinemaOrbit(
    progress: Float,
    color: Color,
    radius: Float,
) {
    if (progress <= 0f || progress >= 1f) return
    val alpha = (progress * 6f).coerceAtMost(1f) * (1f - progress)
    repeat(6) { index ->
        val head = center + cinemaOrbitPoint(progress, index, radius)
        val tail = center + cinemaOrbitPoint((progress - 0.06f).coerceAtLeast(0f), index, radius)
        drawLine(color.copy(alpha = alpha * 0.4f), tail, head, strokeWidth = 1.dp.toPx())
        drawCircle(color.copy(alpha = alpha), radius = 1.5.dp.toPx(), center = head)
    }
}

/** A restored hero starts at rest; only selecting a different title starts its choreography. */
@Composable
fun rememberCinemaHeroProgress(identity: Any?): State<Float> {
    val enabled = cinemaMotion()
    val progress = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(identity) }
    LaunchedEffect(identity, enabled) {
        val changed = previous != identity
        previous = identity
        if (enabled && changed) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(CinemaMotion.HERO, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return progress.asState()
}

@Composable
fun Modifier.cinemaHeroLayer(
    progress: State<Float>,
    stage: Int,
): Modifier {
    val enabled = cinemaMotion()
    if (!enabled) return this
    return graphicsLayer {
        translationY = if (enabled) cinemaCaptionOffset(progress.value, stage).dp.toPx() else 0f
    }
}
