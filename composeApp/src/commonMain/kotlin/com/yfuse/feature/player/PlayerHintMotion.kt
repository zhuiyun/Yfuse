package com.yfuse.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import kotlin.math.roundToInt

/** Both floating hints follow the player's existing 180ms chrome curve from the same clock. */
@Composable
internal fun rememberPlayerHintProgress(controlsVisible: Boolean): State<Float> {
    val target = if (controlsVisible) 1f else 0f
    return if (LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current) {
        // Remove the animator when policy changes mid-flight, instead of only changing its spec.
        rememberUpdatedState(target)
    } else {
        animateFloatAsState(
            targetValue = target,
            animationSpec = tween(Motion.STANDARD, easing = Motion.Curve),
            label = "player-hint-chrome",
        )
    }
}

/** Placement moves the hit target with the hint; fractional progress never changes measurement. */
internal fun Modifier.playerHintOffset(
    progress: State<Float>,
    travel: Dp,
): Modifier = offset { IntOffset(0, (travel.toPx() * progress.value.coerceIn(0f, 1f)).roundToInt()) }
