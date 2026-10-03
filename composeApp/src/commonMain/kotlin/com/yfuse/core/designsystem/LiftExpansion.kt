package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ---------------------------------------------------------------- 一镜到底: the card opens the page
//
// Releasing on a lifted poster's card, or tapping 打开, used to fade the card where it was while
// the grid cell it had left — empty, since the card *was* the poster — flew a second copy of the
// artwork into the detail hero: two motions at once. Now the card itself is the shot. It takes
// off on [Motion.oneTake] carrying the finger's speed, grows into the hero's rect as the page
// arrives under it, rounds its corners off and turns from the portrait poster into the landscape
// art on the way. On landing it hands over: the hero shows itself under the card in that frame,
// and the card fades from on top of a picture identical to its own while the page's words rise
// in. The menu's rows are gone in the first 120 ms. A back before it lands flies it home into its
// poster instead, the way it came.
//
// 静息 and 减弱动态效果 never get here: the card fades as it always has and the page arrives the
// calm way (200 ms fade and 8 dp) or at once.

/**
 * A lifted card opening into the page whose hero shares [key] (see the notes above). Held by the
 * [LiftSession] while it runs; the detail hero finds it through [rememberLiftExpansion].
 */
@Stable
internal class LiftExpansion(
    val key: MediaSharedElementKey,
    /** The finger's speed as it let go, in pixels a second; nothing for a tap on 打开. */
    val velocity: Offset,
) {
    /** The hero the card lands on, once the page being opened has laid it out. */
    internal var target by mutableStateOf<LayoutCoordinates?>(null)

    /** Whether the hero's page is still the one in front; popped before the card lands, it goes home. */
    internal var shown by mutableStateOf<State<Boolean>?>(null)

    /** The hero left the composition before the card reached it. */
    internal var detached by mutableStateOf(false)

    /** The hero's picture has arrived; a card handing over before it would uncover an empty frame. */
    internal var heroReady by mutableStateOf(false)

    /**
     * The page was too slow to lay its hero out and the card faded where it was: the hero, when
     * it comes, is simply there, as on a page opened any other way.
     */
    internal var gaveUp by mutableStateOf(false)

    /** The card has landed and handed over: the hero draws itself again and the page's words rise. */
    var landed by mutableStateOf(false)
        internal set

    /** The card can no longer land here: the page was popped, or its hero went away. */
    val gone: Boolean get() = detached || shown?.value == false

    /**
     * The hero's rect in root pixels. Unclipped: the page's list clips its first item, and the card
     * has to land on the whole picture, not on the part of it the list lets through.
     */
    fun targetBounds(): Rect? {
        val hero = target?.takeIf { it.isAttached } ?: return null
        return hero.findRootCoordinates().localBoundingBoxOf(hero, clipBounds = false)
    }
}

/** The lifted card opening into the page whose hero is [key], while one is on its way. */
@Composable
internal fun rememberLiftExpansion(key: MediaSharedElementKey?): LiftExpansion? {
    val menu = LocalLiftMenu.current ?: return null
    if (key == null) return null
    // Derived per hero: a lift elsewhere in the app changes nothing here.
    val expansion by remember(menu, key) {
        derivedStateOf { menu.session?.expansion?.takeIf { it.key == key } }
    }
    return expansion
}

/**
 * The hero a lifted card is landing on: reports where it is and whether its page is still in
 * front, and keeps itself hidden until the card has handed over, so the two never show at once.
 */
@Composable
internal fun Modifier.liftExpansionTarget(expansion: LiftExpansion?): Modifier {
    if (expansion == null) return this
    val shown = rememberRouteVisibility()
    DisposableEffect(expansion, shown) {
        expansion.shown = shown
        onDispose { expansion.detached = true }
    }
    return this
        .onPlaced { expansion.target = it }
        .graphicsLayer { alpha = if (expansion.landed || expansion.gaveUp) 1f else 0f }
}

/**
 * The card on its way: where it set off from, and how far it has come towards the hero — or, when
 * the page went before it landed, back towards its poster. Read by the host's card every frame, in
 * layout and drawing only.
 */
@Stable
internal class LiftFlight {
    /** 0 where the card set off, 1 on the hero; or, flying home, 1 on its poster. */
    val progress = Animatable(0f)

    /** The card's own opacity: whole until it has handed over, then gone over the hero. */
    val alpha = Animatable(1f)

    /** Carrying a page open: the card keeps its own [alpha] while the dimming clears around it. */
    var carrying by mutableStateOf(false)
        private set

    /** Taken off: the card is drawn at [frame] from now on, no longer from the lift. */
    var flying by mutableStateOf(false)
        private set

    private var from = Rect.Zero
    private var fromCorner = 0f
    private var fromArt = 0f
    private var fromWords = 0f
    private var toCorner = 0f
    private var toArt = 1f
    private var destination: () -> Rect? = { null }
    private var lastDestination: Rect? = null

    /** The flight's speed, in flights a second, at the moment its page went: the way home starts at it. */
    private var speedAtTurn = 0f

    /** The card's frame now, in root pixels. It follows the hero as the page slides in under it. */
    fun frame(): Rect {
        val to = destination()?.also { lastDestination = it } ?: lastDestination ?: return from
        return lerp(from, to, progress.value)
    }

    /** Its corner radius in pixels: the card's own at take-off, none on the hero. */
    fun cornerPx(): Float = liftFlightBlend(fromCorner, toCorner, progress.value)

    /** How much of the landscape art shows over the poster's own: all of it on the hero. */
    fun art(): Float = liftFlightBlend(fromArt, toArt, progress.value)

    /** The card's words, gone in the first stretch: the page's own arrive after the hand-over. */
    fun words(): Float = liftFlightWords(fromWords, progress.value)

    /**
     * Flies [expansion]'s card from [resting] — where the lift has it, [cornerPx] round, showing
     * [art] of the landscape art and [words] of its words — into the page's hero, and hands over.
     * Home into [home] instead, [homeCornerPx] round, if the page goes first. Returns once the card
     * has faded from the hero or come home; the host then finishes the lift.
     */
    suspend fun carry(
        expansion: LiftExpansion,
        resting: Rect,
        cornerPx: Float,
        art: Float,
        words: Float,
        home: Rect,
        homeCornerPx: Float,
    ) {
        carrying = true
        takeOff(resting, cornerPx, art, words)
        // A page from the cache lays its hero out in the frame the push begins; one still loading
        // gets a moment, then the card gives up and fades where it is.
        val found =
            withTimeoutOrNull(LIFT_EXPAND_WAIT_MS) {
                snapshotFlow { expansion.gone || expansion.targetBounds() != null }.first { it }
            }
        if (found == null) {
            expansion.gaveUp = true
            alpha.animateTo(0f, Motion.tween(Motion.QUICK))
            return
        }
        if (!expansion.gone) {
            val hero = expansion.targetBounds() ?: resting
            destination = { expansion.targetBounds() }
            toCorner = 0f
            toArt = 1f
            flying = true
            val speed = zoomBackFlightVelocity(expansion.velocity, resting, hero)
            if (flyUnlessGone(expansion, speed)) {
                // Landed. The hero's picture takes over as soon as it is there to take over.
                withTimeoutOrNull(LIFT_HERO_READY_WAIT_MS) {
                    snapshotFlow { expansion.heroReady || expansion.gone }.first { it }
                }
                if (!expansion.gone) {
                    expansion.landed = true
                    alpha.animateTo(0f, Motion.tween(Motion.QUICK))
                    return
                }
            }
        }
        flyHome(home, homeCornerPx)
    }

    private fun takeOff(
        resting: Rect,
        cornerPx: Float,
        art: Float,
        words: Float,
    ) {
        from = resting
        fromCorner = cornerPx
        fromArt = art
        fromWords = words
        destination = { resting }
    }

    /** The spring towards the hero, cut short if its page goes. True when the card got there. */
    private suspend fun flyUnlessGone(
        expansion: LiftExpansion,
        speed: Float,
    ): Boolean =
        coroutineScope {
            val flight =
                launch {
                    progress.snapTo(0f)
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = Motion.oneTake(Motion.ONE_TAKE_PROGRESS_THRESHOLD),
                        initialVelocity = speed,
                    )
                }
            val watch =
                launch {
                    snapshotFlow { expansion.gone }.first { it }
                    // Read before the cancel: a cancelled Animatable forgets its velocity.
                    speedAtTurn = progress.velocity
                    flight.cancel()
                }
            flight.join()
            watch.cancel()
            !expansion.gone
        }

    /**
     * The page went before the card handed over: back into [home], from wherever the card is and
     * at the speed it has, turning back into the poster on the way.
     */
    private suspend fun flyHome(
        home: Rect,
        homeCornerPx: Float,
    ) {
        val now = frame()
        val way = (lastDestination ?: now).center - from.center
        val moving = Offset(way.x * speedAtTurn, way.y * speedAtTurn)
        fromCorner = cornerPx()
        fromArt = art()
        fromWords = words()
        from = now
        destination = { home }
        toCorner = homeCornerPx
        toArt = 0f
        flying = true
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = Motion.oneTake(Motion.ONE_TAKE_PROGRESS_THRESHOLD),
            initialVelocity = zoomBackFlightVelocity(moving, now, home),
        )
    }
}

/** [from] to [to] by [fraction], held at the ends: a spring's overshoot must not round a corner past zero. */
internal fun liftFlightBlend(
    from: Float,
    to: Float,
    fraction: Float,
): Float = from + (to - from) * fraction.coerceIn(0f, 1f)

/** The card's words over its flight: [from] at take-off, gone by [LIFT_WORDS_GONE] of the way. */
internal fun liftFlightWords(
    from: Float,
    fraction: Float,
): Float = from * (1f - fraction / LIFT_WORDS_GONE).coerceIn(0f, 1f)

/**
 * How many times the lifted [card]'s size the landscape art is laid out at so that it covers
 * [frame] — once the card has grown past itself on its way into a hero — and never less than 1.
 */
internal fun liftArtCover(
    frame: Rect,
    card: Rect,
): Float {
    if (card.width <= 0f || card.height <= 0f) return 1f
    return maxOf(1f, frame.width / card.width, frame.height / card.height)
}

/** How long a card waits for the page it opened to lay its hero out before it fades instead. */
private const val LIFT_EXPAND_WAIT_MS = 600L

/** How long a landed card waits for the hero's picture before handing over regardless. */
private const val LIFT_HERO_READY_WAIT_MS = 300L

/** The card's words are gone a quarter of the way to the hero. */
internal const val LIFT_WORDS_GONE = 0.25f

// ---------------------------------------------------------------- the page's words arrive

/** The rows a page's words arrive in, one [Motion.SEARCH_ROW_STAGGER] after another. */
internal const val ONE_TAKE_ARRIVAL_ROWS = 6

/** The whole arrival: the last row starts five beats in and takes [Motion.STANDARD]. */
private const val ONE_TAKE_ARRIVAL_MS = (ONE_TAKE_ARRIVAL_ROWS - 1) * Motion.SEARCH_ROW_STAGGER + Motion.STANDARD

/** How far each row rises into place. */
private val OneTakeRise = 8.dp

/**
 * How far row [order] has arrived when the arrival's clock reads [clock] (0..1 over the whole
 * arrival): each row [Motion.SEARCH_ROW_STAGGER] ms after the one before, over [Motion.STANDARD]
 * on the house curve.
 */
internal fun oneTakeArrivalProgress(
    clock: Float,
    order: Int,
): Float {
    val elapsed = clock.coerceIn(0f, 1f) * ONE_TAKE_ARRIVAL_MS
    val start = order.coerceIn(0, ONE_TAKE_ARRIVAL_ROWS - 1) * Motion.SEARCH_ROW_STAGGER
    return Motion.Curve.transform(((elapsed - start) / Motion.STANDARD).coerceIn(0f, 1f))
}

/**
 * Whether a page's words still wait for [expansion]'s card: only while it is on its way to the
 * hero and still [current], the lift's expansion now. Once the card has handed over, given up on
 * a page slow to lay its hero out or lost its page — or its lift ended some other way — nothing
 * will hand over, and words left waiting for it would never show.
 */
internal fun oneTakeHoldsWords(
    expansion: LiftExpansion,
    current: LiftExpansion?,
): Boolean = current === expansion && !expansion.landed && !expansion.gaveUp && !expansion.gone

/**
 * The clock a page's words rise in on after a lifted card has opened it: held at 0 while the card
 * is on its way over them, running from the moment it hands over. Null for a page opened any other
 * way, whose words are simply there.
 */
@Composable
internal fun rememberOneTakeArrival(itemId: String): State<Float>? {
    val menu = LocalLiftMenu.current ?: return null
    // Asked once, as the words first compose. Read without observing: a lift elsewhere must not
    // recompose the page's words.
    val arriving =
        remember(menu, itemId) {
            Snapshot.withoutReadObservation {
                val current = menu.session?.expansion
                current?.takeIf { it.key.itemId == itemId && oneTakeHoldsWords(it, current) }
            }
        } ?: return null
    val clock = remember(arriving) { Animatable(0f) }
    LaunchedEffect(arriving) {
        snapshotFlow { oneTakeHoldsWords(arriving, menu.session?.expansion) }.first { !it }
        clock.animateTo(1f, Motion.tween(ONE_TAKE_ARRIVAL_MS, easing = LinearEasing))
    }
    return clock.asState()
}

/**
 * Row [order] of a page's words on [arrival]'s clock: it rises [OneTakeRise] into place as it
 * fades in. Nothing at all without an arrival. Read while drawing; the rows never recompose.
 */
internal fun Modifier.oneTakeArrival(
    arrival: State<Float>?,
    order: Int,
): Modifier =
    if (arrival == null) {
        this
    } else {
        graphicsLayer {
            val shown = oneTakeArrivalProgress(arrival.value, order)
            alpha = shown
            translationY = (1f - shown) * OneTakeRise.toPx()
        }
    }
