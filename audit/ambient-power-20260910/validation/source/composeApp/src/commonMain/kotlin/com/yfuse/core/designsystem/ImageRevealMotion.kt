package com.yfuse.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState

/** The request owns its reveal. Hidden, cached and reduced-motion images have no running clock. */
@Composable
internal fun rememberImageRevealProgress(
    requestKey: Any,
    loaded: Boolean,
    instant: Boolean,
    enabled: Boolean,
    durationMillis: Int,
): State<Float> {
    val animate =
        enabled &&
            durationMillis > 0 &&
            !instant &&
            LocalRouteVisible.current &&
            !LocalAccessibilityOptions.current.reduceMotion
    return key(requestKey) {
        if (animate) {
            animateFloatAsState(
                targetValue = if (loaded) 1f else 0f,
                animationSpec = tween(durationMillis, easing = Motion.Curve),
                label = "imageIn",
            )
        } else {
            // Bypass the animation on this draw, even when policy changes during an entrance.
            // Re-entering the animated branch with a loaded image starts at its resting value.
            rememberUpdatedState(1f)
        }
    }
}
