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

    /** How much of the artwork its window shows, in square pixels. */
    fun visibleArea(): Float {
        val shown = bounds.intersect(viewport)
        return if (shown.width > 0f && shown.height > 0f) shown.width * shown.height else 0f
    }
}

/**
 * Geometry and URLs, plus — only for as long as an artwork is composed — the way to ask its window
 * about the display. A tap and a launch keep nothing but geometry and URLs: never an Activity, a
 * View, a bitmap or layout coordinates.
 */
internal object PlayerArtworkOrigins {
    private class Entry(
        val origin: PlayerArtworkOrigin,
        val screen: ScreenGeometrySource?,
    )

    private val sources = linkedMapOf<Any, Entry>()
    private var pending: Pair<PlayerArtworkOrigin, TimeMark>? = null
    private var sequence = 0L
    private val launches = linkedMapOf<Long, Pair<HandoffLaunch, TimeMark>>()

    /**
     * [origin] as laid out in its window. [screen] is asked about the display only when a tap
     * starts a launch from it: the artwork is laid out again on every frame a list or the reel
     * moves, and each of those questions is a round trip to the system.
     */
    fun register(
        owner: Any,
        origin: PlayerArtworkOrigin,
        screen: ScreenGeometrySource? = null,
    ) {
        sources[owner] = Entry(origin, screen)
        while (sources.size > 96) sources.remove(sources.keys.first())
    }

    fun remove(owner: Any) {
        sources.remove(owner)
    }

    /** Where the artwork for [key] is now, on the display as it is now. */
    fun resolve(key: MediaSharedElementKey): PlayerArtworkOrigin? =
        entryFor(key)?.let { entry -> entry.screen?.let { entry.origin.copy(screen = it.current()) } ?: entry.origin }

    /**
     * A hero and a poster further down the page can carry the same title's key, and the play key
     * belongs to the hero: of the artworks registered for [key], the one showing the most of
     * itself — the most recent, between equals.
     */
    private fun entryFor(key: MediaSharedElementKey): Entry? =
        sources.values
            .filter { it.origin.key == key }
            .asReversed()
            .maxByOrNull { it.origin.visibleArea() }

    fun begin(key: MediaSharedElementKey?) {
        pending = key?.let(::resolve)?.let { it to TimeSource.Monotonic.markNow() }
    }

    /**
     * Turns the pending tap into a launch the player can claim with the returned token, and
     * starts the page's half of [style] at this moment — the one at which the player is really on
     * its way, rather than at the tap, which may still end in a version picker or an error.
     *
     * No token when nothing is pending, the tap is stale, the artwork has scrolled mostly off
     * the screen, or [style] is [PlayerTransitionStyle.None]: the player then opens with the plain
     * window fade.
     */
    fun issueLaunch(style: PlayerTransitionStyle): Long? {
        val candidate = pending.also { pending = null } ?: return null
        if (!style.choreographed) return null
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

/**
 * Offers this artwork as the start of a player transition. Put it only where a launch can start
 * — a hero with its play key — since every registration is laid out again each frame it moves.
 * The layout callback records window bounds and nothing else; the display is asked once, at the tap.
 */
@Composable
internal fun Modifier.playerArtworkSource(
    key: MediaSharedElementKey?,
    urls: List<String?>,
): Modifier {
    val owner = remember { Any() }
    val enabled = key != null && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val screen = rememberScreenGeometrySource()
    val candidates = remember(urls) { urls.filterNotNull().filter(String::isNotBlank) }
    DisposableEffect(owner, enabled) { onDispose { PlayerArtworkOrigins.remove(owner) } }
    return onGloballyPositioned { coordinates ->
        if (enabled && key != null) {
            val bounds = coordinates.boundsInWindow()
            val viewport = coordinates.findRootCoordinates().boundsInWindow()
            if (bounds.width > 0f &&
                bounds.height > 0f &&
                viewport.width > 0f &&
                viewport.height > 0f &&
                candidates.isNotEmpty()
            ) {
                PlayerArtworkOrigins.register(owner, PlayerArtworkOrigin(key, bounds, viewport, candidates), screen)
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
