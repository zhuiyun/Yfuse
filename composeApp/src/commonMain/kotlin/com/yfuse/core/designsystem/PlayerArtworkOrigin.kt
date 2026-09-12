package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.time.TimeSource

internal data class PlayerArtworkOrigin(
    val key: MediaSharedElementKey,
    val bounds: Rect,
    val viewport: Rect,
    val urls: List<String>,
)

/** Geometry/URLs only. Never retains Activities, Views, bitmaps, or layout coordinates. */
internal object PlayerArtworkOrigins {
    private val sources = linkedMapOf<Any, PlayerArtworkOrigin>()
    private var pending: Pair<PlayerArtworkOrigin, kotlin.time.TimeMark>? = null
    private var sequence = 0L
    private val launches = linkedMapOf<Long, Pair<PlayerArtworkOrigin, kotlin.time.TimeMark>>()

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

    fun issueLaunch(): Long? {
        val candidate = pending.also { pending = null } ?: return null
        if (candidate.second.elapsedNow().inWholeMilliseconds > 5000L) return null
        val token = ++sequence
        launches[token] = candidate
        while (launches.size > 4) launches.remove(launches.keys.first())
        return token
    }

    fun consume(token: Long): PlayerArtworkOrigin? =
        launches
            .remove(token)
            ?.takeIf {
                it.second.elapsedNow().inWholeMilliseconds <= 10000L
            }?.first
}

@Composable
internal fun Modifier.playerArtworkSource(
    key: MediaSharedElementKey?,
    urls: List<String?>,
): Modifier {
    val owner = remember { Any() }
    val enabled = key != null && LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
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
                PlayerArtworkOrigins.register(owner, PlayerArtworkOrigin(key, bounds, viewport, candidates))
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

/**
 * Normalized coordinates survive a phone's portrait/landscape Activity boundary.
 *
 * When the two windows share an orientation (a tablet, a television) the mapping is exact and
 * the artwork lands on the very pixels it came from. When they do not, the phone physically
 * rotates between the two Activities and no rectangle in the player's window is where the
 * artwork will actually be. Mapping a full-width hero straight across turned it into a strip
 * along the top edge, so the departure read as a sliver of picture over a black screen. The
 * mapped area therefore only fixes the position: the artwork keeps its own aspect ratio and
 * shrinks as a whole, centred inside that area.
 */
internal fun playerArtworkRect(
    origin: PlayerArtworkOrigin,
    width: Float,
    height: Float,
): Rect {
    val viewport = origin.viewport

    fun x(value: Float) = ((value - viewport.left) / viewport.width).coerceIn(0f, 1f) * width

    fun y(value: Float) = ((value - viewport.top) / viewport.height).coerceIn(0f, 1f) * height
    val mapped = Rect(x(origin.bounds.left), y(origin.bounds.top), x(origin.bounds.right), y(origin.bounds.bottom))
    val rotated = (viewport.width > viewport.height) != (width > height)
    val aspect = origin.bounds.width / origin.bounds.height
    if (!rotated || !aspect.isFinite() || aspect <= 0f || mapped.width <= 0f || mapped.height <= 0f) return mapped
    val fittedWidth = minOf(mapped.width, mapped.height * aspect)
    val fittedHeight = minOf(mapped.height, mapped.width / aspect)
    val center = mapped.center
    return Rect(
        center.x - fittedWidth / 2f,
        center.y - fittedHeight / 2f,
        center.x + fittedWidth / 2f,
        center.y + fittedHeight / 2f,
    )
}
