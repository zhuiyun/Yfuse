package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
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
 * An artwork that can start a player transition. It is asked where it is only when a launch wants
 * to know — the tap — never on the frames a list or the reel moves it.
 */
internal interface PlayerArtworkSource {
    val key: MediaSharedElementKey

    /** Asked about the display once, at the tap; null keeps the window's own frame. */
    val screen: ScreenGeometrySource?

    /** Where the artwork is in its window now; null while it cannot start a launch. */
    fun measure(): PlayerArtworkOrigin?
}

/**
 * The artworks on screen that could start a launch, plus the one launch in flight. What is held per
 * artwork is a handle for as long as it is composed; a tap and a launch keep nothing but geometry
 * and URLs: never an Activity, a View, a bitmap or layout coordinates.
 */
internal object PlayerArtworkOrigins {
    /** A registration made with its geometry already measured, as a test or a fixed hero does. */
    private class MeasuredSource(
        private val origin: PlayerArtworkOrigin,
        override val screen: ScreenGeometrySource?,
    ) : PlayerArtworkSource {
        override val key: MediaSharedElementKey get() = origin.key

        override fun measure(): PlayerArtworkOrigin = origin
    }

    private val sources = linkedMapOf<Any, PlayerArtworkSource>()
    private var pending: Pair<PlayerArtworkOrigin, TimeMark>? = null
    private var sequence = 0L
    private val launches = linkedMapOf<Long, Pair<HandoffLaunch, TimeMark>>()

    /**
     * [origin] as already laid out in its window. [screen] is asked about the display only when a
     * tap starts a launch from it: each of those questions is a round trip to the system.
     */
    fun register(
        owner: Any,
        origin: PlayerArtworkOrigin,
        screen: ScreenGeometrySource? = null,
    ) {
        sources[owner] = MeasuredSource(origin, screen)
        trim()
    }

    /** [source] for as long as it stays attached; it is measured only by [resolve]. */
    fun attach(source: PlayerArtworkSource) {
        sources[source] = source
        trim()
    }

    fun remove(owner: Any) {
        sources.remove(owner)
    }

    /** Where the artwork for [key] is now, on the display as it is now. */
    fun resolve(key: MediaSharedElementKey): PlayerArtworkOrigin? {
        val (source, origin) = measuredFor(key) ?: return null
        return source.screen?.let { origin.copy(screen = it.current()) } ?: origin
    }

    /**
     * A hero and a poster further down the page can carry the same title's key, and the play key
     * belongs to the hero: of the artworks attached for [key], the one showing the most of itself —
     * the most recent, between equals. Only the few carrying [key] are measured.
     */
    private fun measuredFor(key: MediaSharedElementKey): Pair<PlayerArtworkSource, PlayerArtworkOrigin>? =
        sources.values
            .filter { it.key == key }
            .asReversed()
            .mapNotNull { source -> source.measure()?.let { source to it } }
            .maxByOrNull { it.second.visibleArea() }

    /**
     * A guard against a registration outliving its artwork, not a working limit: attached artwork
     * leaves again as it detaches, so a full grid and its rails stay well below this.
     */
    private fun trim() {
        while (sources.size > MAX_SOURCES) sources.remove(sources.keys.first())
    }

    fun begin(key: MediaSharedElementKey?) {
        pending = key?.let(::resolve)?.let { it to TimeSource.Monotonic.markNow() }
    }

    /**
     * The page was touched again (see [playerHandoffStage]). A tap that ended in a picker or an
     * error instead of a player must not lend its artwork, or its key, to whatever launches next:
     * a 浮起菜单's 播放 starts from a long press and never calls [begin], and the launch carries
     * nothing to tell whose it is. A tap that does launch has begun after its own touch.
     */
    fun pageTouched() {
        pending = null
        PlayerHandoff.forgetKey()
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

/** See [PlayerArtworkOrigins.trim]. */
private const val MAX_SOURCES = 256

/**
 * Offers this artwork as the start of a player transition, wherever a launch may start from it — a
 * hero with its play key, a 继续观看 card whose tap plays.
 *
 * Every media card carries one, so it has to cost nothing while the page moves: the node is a
 * handle that joins [PlayerArtworkOrigins] as it attaches and leaves as it detaches. Its bounds are
 * measured only when a tap asks for them, and the display only then too. It used to measure itself
 * on every layout pass — twice, then a map write — for each card, on every frame of a scroll.
 */
@Composable
internal fun Modifier.playerArtworkSource(
    key: MediaSharedElementKey?,
    urls: List<String?>,
): Modifier {
    val screen = rememberScreenGeometrySource()
    // Read when the tap asks rather than in composition, so a push or pop does not recompose every card.
    val visibility = rememberRouteVisibility()
    val reduced = LocalAccessibilityOptions.current.reduceMotion
    val candidates = remember(urls) { urls.filterNotNull().filter(String::isNotBlank) }
    if (key == null || candidates.isEmpty()) return this
    return this then PlayerArtworkSourceElement(key, candidates, screen, visibility, reduced)
}

private data class PlayerArtworkSourceElement(
    val key: MediaSharedElementKey,
    val urls: List<String>,
    val screen: ScreenGeometrySource,
    val visibility: State<Boolean>,
    val reduced: Boolean,
) : ModifierNodeElement<PlayerArtworkSourceNode>() {
    override fun create(): PlayerArtworkSourceNode = PlayerArtworkSourceNode(key, urls, screen, visibility, reduced)

    override fun update(node: PlayerArtworkSourceNode) {
        node.key = key
        node.urls = urls
        node.screen = screen
        node.visibility = visibility
        node.reduced = reduced
    }
}

private class PlayerArtworkSourceNode(
    override var key: MediaSharedElementKey,
    var urls: List<String>,
    override var screen: ScreenGeometrySource?,
    var visibility: State<Boolean>,
    var reduced: Boolean,
) : Modifier.Node(),
    PlayerArtworkSource {
    // Nothing here draws, lays out or handles input, so an update has nothing to invalidate.
    override val shouldAutoInvalidate: Boolean
        get() = false

    override fun onAttach() {
        PlayerArtworkOrigins.attach(this)
    }

    override fun onDetach() {
        PlayerArtworkOrigins.remove(this)
    }

    override fun measure(): PlayerArtworkOrigin? {
        // No launch under 减弱动态效果, and none from a page another one covers.
        if (!isAttached || reduced || !visibility.value) return null
        val coordinates =
            try {
                requireLayoutCoordinates()
            } catch (_: IllegalStateException) {
                // Attached but not placed yet: nothing on screen to fly from.
                return null
            }
        val bounds = coordinates.boundsInWindow()
        val viewport = coordinates.findRootCoordinates().boundsInWindow()
        if (bounds.width <= 0f || bounds.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) return null
        return PlayerArtworkOrigin(key, bounds, viewport, urls)
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
