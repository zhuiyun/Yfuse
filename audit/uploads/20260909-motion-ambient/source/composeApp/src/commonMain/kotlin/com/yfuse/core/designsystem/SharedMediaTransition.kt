package com.yfuse.core.designsystem

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    var popSuppressed: Boolean = false
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
internal fun isSharedMediaArtworkActive(key: MediaSharedElementKey?): Boolean =
    key != null &&
        !LocalAccessibilityOptions.current.reduceMotion &&
        LocalSharedMediaTransitionController.current?.let {
            it.activeKey == key && !it.popSuppressed
        } == true &&
        LocalSharedTransitionScope.current != null

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
            boundsTransform = { _, _ ->
                tween(durationMillis = Motion.EXPAND, easing = Motion.Curve)
            },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}
