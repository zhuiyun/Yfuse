package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The "a request is in flight" pulse: a soft glow wandering left and right inside the
 * control, and a highlight travelling around its rim on the same beat.
 *
 * It started life on the search field alone. Every other waiting control — 登录中, 连接并登录,
 * 播放 while the source resolves, 收藏 while it saves — only had the orb, so a field that was
 * searching looked alive and a button that was working looked stuck. This is that same pulse
 * as a modifier, so the two read as the same state.
 *
 * Draws nothing until [active] has held for [WAITING_PULSE_DELAY_MS]: a fast response never
 * flashes an ornament. Reduced motion or reduced transparency draws nothing at all.
 */
@Composable
fun Modifier.waitingPulse(
    active: Boolean,
    shape: Shape,
    color: Color = LocalAccentColors.current.accent,
): Modifier {
    val accessibility = LocalAccessibilityOptions.current
    val moving = LocalRouteVisible.current && !accessibility.reduceMotion && !accessibility.reduceTransparency
    val waiting = active && moving
    val pulse = remember { Animatable(0f) }
    val shown = remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        pulse.snapTo(0f)
        shown.value = false
        if (!waiting) return@LaunchedEffect
        delay(WAITING_PULSE_DELAY_MS)
        shown.value = true
        val durationScale = coroutineContext[MotionDurationScale]
        while (true) {
            if (durationScale?.scaleFactor == 0f) {
                pulse.snapTo(0f)
                shown.value = false
                snapshotFlow { durationScale.scaleFactor }.first { it > 0f }
                shown.value = true
            }
            pulse.animateTo(1f, tween(WAITING_PULSE_LEG_MS, easing = Motion.Curve))
            pulse.animateTo(0f, tween(WAITING_PULSE_LEG_MS, easing = Motion.Curve))
            delay(16)
        }
    }
    if (!waiting) return this
    return drawBehind {
        if (!shown.value) return@drawBehind
        val value = pulse.value
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = Path().apply { addOutline(outline) }
        clipPath(clip) {
            drawRect(
                brush =
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.20f + 0.12f * value), Color.Transparent),
                        center = Offset(size.width * (0.15f + value * 0.60f), size.height * 0.5f),
                        radius = (size.width * 0.65f).coerceAtLeast(1f),
                    ),
            )
            // A centred stroke twice the token, clipped to the shape: exactly 1dp inside the rim.
            drawOutline(
                outline,
                brush =
                    Brush.linearGradient(
                        listOf(color.copy(alpha = 0.12f), color.copy(alpha = 0.72f), color.copy(alpha = 0.12f)),
                        start = Offset(size.width * (value - 0.5f), 0f),
                        end = Offset(size.width * (value + 0.5f), size.height),
                    ),
                style = Stroke(1.dp.toPx() * 2f),
            )
        }
    }
}

const val WAITING_PULSE_LEG_MS = Motion.WAIT_HALF_CYCLE
const val WAITING_PULSE_DELAY_MS = Motion.STANDARD * 1L
