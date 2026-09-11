package com.yfuse.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState

/** A decorative clock exists only while its route, feature and accessibility policy allow it. */
@Composable
internal fun rememberDecorativePhase(
    enabled: Boolean = true,
    periodMillis: Int,
    rest: Float = 0f,
    repeatMode: RepeatMode = RepeatMode.Restart,
    label: String,
): State<Float> =
    if (enabled && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion) {
        rememberInfiniteTransition(label = label).animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), repeatMode),
            label = label + "Phase",
        )
    } else {
        rememberUpdatedState(rest)
    }
