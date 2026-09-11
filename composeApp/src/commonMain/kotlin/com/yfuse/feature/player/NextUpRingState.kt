package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion

internal class NextUpRingState(
    remainingMs: Long,
) {
    private val remaining = Animatable(remainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS).toFloat())
    val value: State<Float> = remaining.asState()

    suspend fun retarget(
        remainingMs: Long,
        advancing: Boolean,
        speed: Float,
    ) {
        val current = remainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS).toFloat()
        remaining.snapTo(current)
        if (advancing) {
            // Never run a free-running countdown: one bounded prediction per engine sample.
            remaining.animateTo(
                nextUpPredictedRemaining(current, speed),
                tween(Motion.NEXT_UP_INTERPOLATION, easing = LinearEasing),
            )
        }
    }
}

internal fun nextUpPredictedRemaining(
    remainingMs: Float,
    speed: Float,
): Float =
    (remainingMs - Motion.NEXT_UP_INTERPOLATION * speed.takeIf { it.isFinite() && it > 0f }.orDefaultSpeed())
        .coerceAtLeast(0f)

private fun Float?.orDefaultSpeed(): Float = this ?: 1f

@Composable
internal fun rememberNextUpRemaining(
    key: Any,
    remainingMs: Long,
    advancing: Boolean,
    speed: Float,
): State<Float> {
    val motion = remember(key) { NextUpRingState(remainingMs) }
    val animate = advancing && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    LaunchedEffect(motion, remainingMs, animate, speed) { motion.retarget(remainingMs, animate, speed) }
    return motion.value
}
