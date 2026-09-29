package com.yfuse.core.designsystem

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
 * Starts a shared transition for a deliberate forward tap, and runs it backwards for the back
 * that returns to the same poster (一镜到底, see [onPop]).
 *
 * The key is cleared once a morph has settled, so a later predictive pop — which 跟手返回 draws
 * itself — never finds a stale morph waiting above its gesture.
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
        noteOrigin(key)
    }

    // The poster a route is about to be pushed from, for 跟手返回 to go back into. Noted on every
    // tap — under 减弱动态效果 too, which has no morph but keeps the pull-down — and claimed by the
    // push that follows it, or by nothing once it is stale.
    private var pendingOrigin: MediaSharedElementKey? = null
    private var pendingSince: TimeSource.Monotonic.ValueTimeMark? = null

    /** A poster with [key] was tapped to open something. */
    fun noteOrigin(key: MediaSharedElementKey) {
        pendingOrigin = key
        pendingSince = TimeSource.Monotonic.markNow()
    }

    /** The poster the route just pushed was opened from, if a tap has just named one. Claims it. */
    fun takeOrigin(): MediaSharedElementKey? {
        val key = pendingOrigin
        val fresh = pendingSince?.let { it.elapsedNow() < OriginClaimWindow } == true
        pendingOrigin = null
        pendingSince = null
        return key?.takeIf { fresh }
    }

    /** A shrinking navigation stack must not reuse the forward-only artwork morph. */
    fun suppressForPop() {
        popSuppressed = true
    }

    /**
     * The stack shrank. Back runs the way in backwards: a morph still under way turns round where
     * it is — the route transition reverses beneath it, and the spring carries its speed into the
     * way back — and a page opened from a poster that is [returnTo] morphs back into it. Anything
     * else keeps the old rule and suppresses the forward morph.
     */
    fun onPop(returnTo: MediaSharedElementKey?) {
        if (activeKey != null && !popSuppressed) return
        if (returnTo == null) {
            suppressForPop()
            return
        }
        popSuppressed = false
        activeKey = returnTo
    }

    fun finish(key: MediaSharedElementKey) {
        if (activeKey == key) activeKey = null
    }
}

/** How long after a tap its push may still claim the poster it came from. */
private val OriginClaimWindow = 1_500.milliseconds

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
        if (key != null) {
            if (reduceMotion) controller?.noteOrigin(key) else controller?.begin(key)
        }
        onClick()
    }
}

/**
 * Register matching artwork for the current one-way media push, if one is active — and, while a
 * 跟手返回 is looking for it, offer it as the place the page goes back into (see [zoomBackSource]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedMediaArtwork(key: MediaSharedElementKey?): Modifier {
    val base = zoomBackSource(key)
    if (!isSharedMediaArtworkActive(key)) return base
    val activeKey = checkNotNull(key)
    val sharedScope = LocalSharedTransitionScope.current ?: return base
    val visibilityScope = LocalNavAnimatedContentScope.current
    return with(sharedScope) {
        base.sharedBounds(
            sharedContentState = rememberSharedContentState(activeKey),
            animatedVisibilityScope = visibilityScope,
            // The poster's picture gives way to the page's on the bounds' own clock, so the two
            // arrive together; the default fades ran on a softer spring of their own.
            enter = fadeIn(Motion.oneTakeFade()),
            exit = fadeOut(Motion.oneTakeFade()),
            // 一镜到底's spring, not the route's 280 ms tween: a back pressed mid-morph turns it
            // round from where it is, at the speed it has, where a tween restarted from the far end.
            boundsTransform = { _, _ -> Motion.oneTake(MorphLandingThreshold) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

/**
 * Where the morph may stop: half a pixel from its bounds. The default hundredth of a pixel kept
 * the transition — and the gestures that wait for it — running for a quarter second longer.
 */
private val MorphLandingThreshold = Rect(0.5f, 0.5f, 0.5f, 0.5f)
