package com.yfuse.core.designsystem

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** Stable identity shared by a library artwork tile and the detail hero it opens. */
data class MediaSharedElementKey(
    val serverId: String?,
    val itemId: String,
    val kind: String = "artwork",
)

/**
 * Starts a shared transition only for a deliberate forward tap.
 *
 * The key is cleared after the push settles, so a later pop remains the hard cut selected
 * for this app instead of silently re-introducing a return animation through the shared
 * element overlay.
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

    /** A shrinking navigation stack must remain a hard cut, even during a fast back tap. */
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
