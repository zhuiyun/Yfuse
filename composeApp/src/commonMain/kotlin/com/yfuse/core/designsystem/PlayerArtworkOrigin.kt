package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class PlayerArtworkOrigin(
    val key: MediaSharedElementKey,
    val bounds: Rect,
    val viewport: Rect,
    val urls: List<String>,
    /** The page window's view of the display when [bounds] was measured. */
    val screen: ScreenGeometry = ScreenGeometry(0, viewport.size),
) {
    /** [bounds] on the display, the frame the player window can map from. */
    val boundsOnScreen: Rect
        get() = bounds.translate(screen.windowOffset)
}

/** Geometry/URLs only. Never retains Activities, Views, bitmaps, or layout coordinates. */
internal object PlayerArtworkOrigins {
    private val sources = linkedMapOf<Any, PlayerArtworkOrigin>()
    private var pending: Pair<PlayerArtworkOrigin, TimeMark>? = null
    private var sequence = 0L
    private val launches = linkedMapOf<Long, Pair<HandoffLaunch, TimeMark>>()

    fun register(
        owner: Any,
        origin: PlayerArtworkOrigin,
    ) {
        sources[owner] = origin
        while (sources.size > 96) sources.remove(sources.keys.first())
    }

    fun remove(owner: Any) {
        sources.remove(owner)
    }

    fun resolve(key: MediaSharedElementKey): PlayerArtworkOrigin? = sources.values.lastOrNull { it.key == key }

    fun begin(key: MediaSharedElementKey?) {
        pending = key?.let(::resolve)?.let { it to TimeSource.Monotonic.markNow() }
    }

    /**
     * Turns the pending tap into a launch the player can claim with the returned token, and
     * starts the page's half of [style] at this moment — the one at which the player is really on
     * its way, rather than at the tap, which may still end in a version picker or an error.
     *
     * No token when nothing is pending, the tap is stale, or the artwork has scrolled mostly off
     * the screen: the player then opens with the plain window fade.
     */
    fun issueLaunch(style: PlayerTransitionStyle): Long? {
        val candidate = pending.also { pending = null } ?: return null
        if (candidate.second.elapsedNow().inWholeMilliseconds > 5000L) return null
        val origin = candidate.first
        val hero = origin.boundsOnScreen
        if (visibleShare(hero, origin.screen.size) < MIN_VISIBLE_SHARE) return null
        val launch =
            HandoffLaunch(
                style = style,
                startedAt = TimeSource.Monotonic.markNow(),
                screen = origin.screen,
                hero = hero,
                urls = origin.urls,
                key = PlayerHandoff.recentKey(),
            )
        PlayerHandoff.begin(launch)
        val token = ++sequence
        launches[token] = launch to TimeSource.Monotonic.markNow()
        while (launches.size > 4) launches.remove(launches.keys.first())
        return token
    }

    fun consume(token: Long): HandoffLaunch? =
        launches
            .remove(token)
            ?.takeIf { it.second.elapsedNow().inWholeMilliseconds <= 10000L }
            ?.first
}

/** Below this share of the artwork on screen there is nothing recognisable left to carry. */
private const val MIN_VISIBLE_SHARE = 0.4f

@Composable
internal fun Modifier.playerArtworkSource(
    key: MediaSharedElementKey?,
    urls: List<String?>,
): Modifier {
    val owner = remember { Any() }
    val enabled = key != null && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val screen = rememberScreenGeometrySource()
    DisposableEffect(owner, enabled) { onDispose { PlayerArtworkOrigins.remove(owner) } }
    return onGloballyPositioned { coordinates ->
        if (enabled && key != null) {
            val bounds = coordinates.boundsInWindow()
            val viewport = coordinates.findRootCoordinates().boundsInWindow()
            val candidates = urls.filterNotNull().filter(String::isNotBlank)
            if (bounds.width > 0f &&
                bounds.height > 0f &&
                viewport.width > 0f &&
                viewport.height > 0f &&
                candidates.isNotEmpty()
            ) {
                val origin = PlayerArtworkOrigin(key, bounds, viewport, candidates, screen.current())
                PlayerArtworkOrigins.register(owner, origin)
            } else {
                PlayerArtworkOrigins.remove(owner)
            }
        }
    }
}

@Composable
internal fun playerArtworkOnClick(
    key: MediaSharedElementKey?,
    onClick: () -> Unit,
): () -> Unit {
    val reduced = LocalAccessibilityOptions.current.reduceMotion
    return {
        PlayerArtworkOrigins.begin(key.takeUnless { reduced })
        onClick()
    }
}
