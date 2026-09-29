package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How one picture takes over from its placeholder; see [ImageRevealMotion]. */
internal data class ImageReveal(
    val durationMillis: Int,
    /** Blur and overscale settle along with the fade. Otherwise the fade is all there is. */
    val resolves: Boolean,
)

/**
 * 图片渐进加载 §3.1: how a picture arrives over its placeholder.
 *
 * A large single picture — a page hero, the detail poster — resolves out of [ResolveBlur] and a
 * [RESOLVE_SCALE_FROM] overscale over [Motion.ARTWORK_REVEAL]. Everything dense, the rails, grids
 * and avatars, only fades, in [Motion.POSTER_FADE]: a grid scrolling quickly would otherwise hold
 * a blur layer per tile in the same frame. 静息 keeps the fade alone and shortens it to
 * [Motion.STATE_HANDOFF] for both, because the resolve is exactly the kind of flourish it takes
 * away. 减弱动态效果 and a picture Coil already held in memory have no reveal at all — that is
 * decided per request by [rememberImageRevealProgress].
 */
internal object ImageRevealMotion {
    /** Large artwork may resolve cinematically, but should never hold the picture soft for long. */
    val ResolveBlur: Dp = 6.dp
    const val RESOLVE_SCALE_FROM = 1.025f

    fun reveal(
        large: Boolean,
        calm: Boolean,
        durationMillis: Int = if (large) Motion.ARTWORK_REVEAL else Motion.POSTER_FADE,
    ): ImageReveal =
        if (calm) {
            ImageReveal(minOf(durationMillis, Motion.STATE_HANDOFF), resolves = false)
        } else {
            ImageReveal(durationMillis, resolves = large)
        }
}

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
