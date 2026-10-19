package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** A restored state is quiet. Hidden/disabled changes are consumed, never replayed on return. */
internal class CinemaChangeTracker(
    initial: Any?,
) {
    private var previous = initial

    fun observe(
        value: Any?,
        moving: Boolean,
    ): Boolean {
        val changed = previous != value
        previous = value
        return moving && changed
    }
}

@Composable
internal fun cinemaChangeProgress(
    value: Any?,
    duration: Int = CinemaMotion.STATUS,
    enabled: Boolean = true,
): State<Float> {
    val moving = cinemaMotion() && enabled
    val tracker = remember { CinemaChangeTracker(value) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(value, moving) {
        if (tracker.observe(value, moving)) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(duration, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return progress.asState()
}

/** The two segments have one continuous drawing speed. */
internal fun cinemaCheckPoints(progress: Float): Pair<Offset, Offset> {
    val start = Offset(0.18f, 0.52f)
    val joint = Offset(0.41f, 0.74f)
    val end = Offset(0.84f, 0.25f)
    val first = (progress.coerceIn(0f, 1f) / 0.4f).coerceIn(0f, 1f)
    val second = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
    return (start + (joint - start) * first) to (joint + (end - joint) * second)
}

internal fun DrawScope.drawCinemaCheck(
    progress: Float,
    color: Color,
) {
    val (joint, end) = cinemaCheckPoints(progress)

    fun Offset.pixels() = Offset(x * size.width, y * size.height)
    drawLine(color, Offset(0.18f, 0.52f).pixels(), joint.pixels(), 1.7.dp.toPx(), StrokeCap.Round)
    if (progress > 0.4f) {
        drawLine(color, Offset(0.41f, 0.74f).pixels(), end.pixels(), 1.7.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
internal fun CinemaSelectionCheck(
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val progress = cinemaChangeProgress(selected, CinemaMotion.CHOICE)
    Canvas(
        modifier.size(14.dp).graphicsLayer {
            val scale = if (selected) cinemaConfirmationScale(progress.value) else 1f
            scaleX = scale
            scaleY = scale
        },
    ) {
        if (selected) drawCinemaCheck(progress.value, color)
    }
}

internal enum class CinemaConnection { Idle, Checking, Connected, Failed, Warning }

/** Only an active operation owns a repeating clock; leaving the route cancels it. */
@Composable
internal fun cinemaBusyProgress(busy: Boolean): State<Float> {
    val moving = cinemaMotion() && busy
    val phase = remember { Animatable(0f) }
    LaunchedEffect(moving) {
        if (moving) {
            while (true) {
                phase.snapTo(0f)
                phase.animateTo(1f, tween(CinemaMotion.SCAN, easing = LinearEasing))
            }
        } else {
            phase.snapTo(0f)
        }
    }
    return phase.asState()
}

@Composable
internal fun CinemaConnectionMark(
    state: CinemaConnection,
    color: Color,
    modifier: Modifier = Modifier,
    television: Boolean = false,
) {
    val change = cinemaChangeProgress(state)
    val busy = cinemaBusyProgress(state == CinemaConnection.Checking)
    Canvas(
        modifier.size(if (television) 30.dp else 14.dp).graphicsLayer {
            translationX =
                if (state == CinemaConnection.Failed) {
                    sin(change.value * PI.toFloat() * 4f) * (1f - change.value) * 3.dp.toPx()
                } else {
                    0f
                }
        },
    ) {
        when {
            state == CinemaConnection.Checking -> {
                val pulse = busy.value
                drawCircle(color.copy(alpha = 0.55f + 0.45f * sin(pulse * PI.toFloat())), size.minDimension * 0.18f)
                repeat(if (television) 2 else 1) { index ->
                    val ring = (pulse + index * 0.5f) % 1f
                    drawCircle(
                        color.copy(alpha = (1f - ring) * 0.6f),
                        size.minDimension * (0.2f + ring * 0.28f),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
            state == CinemaConnection.Connected && television -> {
                val inset = size.width * (0.1f + (1f - change.value) * 0.2f)
                drawRoundRect(
                    color,
                    Offset(inset, inset),
                    Size(size.width - inset * 2f, size.height * 0.65f - inset),
                    style = Stroke(1.5.dp.toPx()),
                )
                drawLine(
                    color,
                    Offset(size.width * 0.35f, size.height * 0.85f),
                    Offset(
                        size.width * 0.65f,
                        size.height * 0.85f,
                    ),
                    1.5.dp.toPx(),
                    StrokeCap.Round,
                )
            }
            state == CinemaConnection.Connected -> drawCinemaCheck(change.value, color)
            else -> drawCircle(color, size.minDimension * 0.23f)
        }
    }
}

@Composable
internal fun Modifier.cinemaReadyRing(
    ready: Boolean,
    color: Color,
): Modifier {
    val progress = cinemaChangeProgress(ready)
    if (!cinemaMotion()) return this
    return drawWithContent {
        drawContent()
        if (ready) {
            val inset = 0.75.dp.toPx()
            drawArc(
                color,
                -90f,
                360f * progress.value,
                false,
                Offset(inset, inset),
                Size(
                    size.width - inset * 2,
                    size.height - inset * 2,
                ),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
internal fun CinemaArrivingItem(
    order: Int = 0,
    content: @Composable () -> Unit,
) {
    val moving = cinemaMotion()
    var visibility by remember { mutableStateOf(MutableTransitionState(!moving)) }
    LaunchedEffect(moving) {
        if (!moving) {
            visibility = MutableTransitionState(true)
        } else {
            if (!visibility.currentState) delay(order.coerceIn(0, 5) * 35L)
            visibility.targetState = true
        }
    }
    if (!moving) {
        Box { content() }
    } else {
        AnimatedVisibility(
            visibleState = visibility,
            enter =
                fadeIn(tween(CinemaMotion.ITEM_ENTER, easing = Motion.Curve)) +
                    expandVertically(tween(CinemaMotion.ITEM_ENTER, easing = Motion.Curve)),
        ) { content() }
    }
}
