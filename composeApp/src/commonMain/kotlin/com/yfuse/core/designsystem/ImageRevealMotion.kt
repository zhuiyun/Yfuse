package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
            !LocalAccessibilityOptions.current.reduceMotion
    // Whether the page is on screen is asked when the picture arrives, not observed: read in
    // composition, every image on the covered and the uncovered page recomposed on a route flip.
    val routeVisibility = rememberRouteVisibility()
    return key(requestKey) {
        if (animate) {
            val progress = remember { Animatable(if (loaded) 1f else 0f) }
            LaunchedEffect(loaded) {
                val target = if (loaded) 1f else 0f
                if (routeVisibility.value) {
                    progress.animateTo(target, tween(durationMillis, easing = Motion.Curve))
                } else {
                    // A page under another has no one watching its pictures arrive.
                    progress.snapTo(target)
                }
            }
            progress.asState()
        } else {
            // Bypass the animation on this draw, even when policy changes during an entrance.
            // Re-entering the animated branch with a loaded image starts at its resting value.
            rememberUpdatedState(1f)
        }
    }
}
