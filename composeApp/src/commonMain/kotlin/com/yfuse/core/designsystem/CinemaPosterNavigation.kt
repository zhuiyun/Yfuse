package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** Only an adjacent return to the actual source route can put a hero back into its poster. */
internal class MediaRouteOrigins {
    private var stack = emptyList<String>()
    private val origins = mutableMapOf<String, Pair<String, MediaSharedElementKey>>()

    fun returningTo(keys: List<String>): MediaSharedElementKey? {
        if (keys.isEmpty() || stack.size != keys.size + 1 || stack.dropLast(1) != keys) return null
        return origins[stack.last()]?.takeIf { it.first == keys.last() }?.second
    }

    fun update(
        keys: List<String>,
        pushedFrom: MediaSharedElementKey?,
    ) {
        // A replacement or a multi-pop must not leave a poster attached to a reused route name.
        val common = stack.zip(keys).takeWhile { (before, after) -> before == after }.size
        origins.keys.retainAll(keys.take(common).toSet())
        if (pushedFrom != null && keys.size == stack.size + 1 && common == stack.size && stack.isNotEmpty()) {
            origins[keys.last()] = stack.last() to pushedFrom
        }
        stack = keys.toList()
    }
}

/** Keep the surfaces stationary while the shared artwork supplies the spatial movement. */
internal fun cinemaPosterPageTransform(popping: Boolean): ContentTransform =
    ContentTransform(
        targetContentEnter = fadeIn(Motion.tween(CinemaMotion.POSTER_PAGE)),
        initialContentExit = fadeOut(Motion.tween(CinemaMotion.POSTER_PAGE)),
        targetContentZIndex = if (popping) -1f else 0f,
        sizeTransform = null,
    )

/** Words follow the first stretch of the poster's flight, and leave first on the reverse. */
internal fun cinemaDetailReveal(progress: Float): Float = ((progress.coerceIn(0f, 1f) - 0.28f) / 0.72f).coerceIn(0f, 1f)

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.cinemaDetailArrival(): Modifier {
    // Both entries participate in a transition, including the one no longer at the stack's top.
    if (!cinemaMotionAllowed(
            LocalMotionTheme.current,
            LocalAccessibilityOptions.current.reduceMotion,
            LocalMotionBudget.current,
            visible = true,
        )
    ) {
        return this
    }
    if (LocalSharedTransitionScope.current == null) return this
    val sideReturn = LocalDetailSideReturn.current
    val progress =
        LocalNavAnimatedContentScope.current.transition.animateFloat(
            transitionSpec = { Motion.tween(CinemaMotion.POSTER_PAGE) },
            label = "cinemaDetailWords",
        ) { if (it == EnterExitState.Visible || (sideReturn && it == EnterExitState.PostExit)) 1f else 0f }
    return graphicsLayer {
        val shown = cinemaDetailReveal(progress.value)
        alpha = shown
        translationY = (1f - shown) * 14.dp.toPx()
    }
}
