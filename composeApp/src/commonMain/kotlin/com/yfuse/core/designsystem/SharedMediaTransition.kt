package com.yfuse.core.designsystem

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** Stable identity shared by a library artwork tile and the detail hero it opens. */
data class MediaSharedElementKey(
    val serverId: String?,
    val itemId: String,
    val kind: String = "artwork",
)

/**
 * A backend can return the same media id more than once in a rail. Compose requires every key
 * in one lazy layout to be unique, so include both the rail scope and the stable occurrence.
 */
internal fun mediaLazyItemKey(
    scope: String,
    index: Int,
    itemId: String,
): String = "media:$scope:$index:$itemId"

/**
 * Starts a shared transition only for a deliberate forward tap.
 *
 * The key is cleared after the push settles, so a later predictive pop remains a simple route
 * transition instead of silently introducing a reverse artwork morph above the gesture.
 */
@Stable
internal class SharedMediaTransitionController {
    var activeKey by mutableStateOf<MediaSharedElementKey?>(null)
        private set

    // State, not a plain field: artwork that reads it has to hear when a pop starts, or a back
    // within the morph's window ran the morph in reverse before cutting to the route.
    var popSuppressed by mutableStateOf(false)
        private set

    fun begin(key: MediaSharedElementKey) {
        popSuppressed = false
        activeKey = key
    }

    /** A shrinking navigation stack must not reuse the forward-only artwork morph. */
    fun suppressForPop() {
        popSuppressed = true
    }

    fun finish(key: MediaSharedElementKey) {
        if (activeKey == key) activeKey = null
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }

internal val LocalSharedMediaTransitionController =
    staticCompositionLocalOf<SharedMediaTransitionController?> { null }

@Composable
internal fun isSharedMediaArtworkActive(key: MediaSharedElementKey?): Boolean {
    val controller = LocalSharedMediaTransitionController.current
    if (key == null || controller == null || LocalSharedTransitionScope.current == null) return false
    // 静息 keeps pages to fades; an artwork flying between them is exactly what it leaves out.
    if (LocalAccessibilityOptions.current.reduceMotion || calmMotion()) return false
    // Derived per poster: a tap changes the answer for one tile, so only that tile recomposes.
    // Comparing in composition made every visible poster recompose on every tap.
    val active by remember(controller, key) {
        derivedStateOf { controller.activeKey == key && !controller.popSuppressed }
    }
    return active
}

/** Wrap a navigation click so matching artwork is registered before the route is pushed. */
@Composable
internal fun sharedMediaOnClick(
    key: MediaSharedElementKey?,
    onClick: () -> Unit,
): () -> Unit {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val controller = LocalSharedMediaTransitionController.current
    return {
        if (!reduceMotion && key != null) controller?.begin(key)
        onClick()
    }
}

/** Register matching artwork for the current one-way media push, if one is active. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedMediaArtwork(key: MediaSharedElementKey?): Modifier {
    if (!isSharedMediaArtworkActive(key)) return this
    val activeKey = checkNotNull(key)
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalNavAnimatedContentScope.current
    return with(sharedScope) {
        this@sharedMediaArtwork.sharedBounds(
            sharedContentState = rememberSharedContentState(activeKey),
            animatedVisibilityScope = visibilityScope,
            // The route's own push length: a 300ms morph under a 280ms push finished after the page
            // it belonged to had already arrived.
            boundsTransform = { _, _ ->
                tween(durationMillis = Motion.PUSH, easing = Motion.Curve)
            },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}
