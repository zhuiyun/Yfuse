package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.time.TimeSource

/**
 * 跟手返回 — a page entered from a poster goes back into that poster.
 *
 * One of these follows one gesture at a time on one host: the pull-down at the top of the page or
 * the system's side swipe. Both end in the same [ZoomCard] geometry (see ZoomBackGeometry.kt), so a
 * release either flies the card into the poster it came from, springs it home, or — with no poster
 * to go to, or under 减弱动态效果 — lets it fade the ordinary way. Either flight starts at the
 * speed the card had, and a finger can catch the card again anywhere along it (see [catchAt]).
 *
 * The host decides what "going back" means (a navigation pop, an overlay closing) through
 * [onStarted], [onFinished] and [resolveTarget], and draws the card with [zoomBackLayers].
 */
@Stable
internal class ZoomBackController(
    private val scope: CoroutineScope,
) {
    // Refreshed by the host on every composition.
    var still = false
    var haptics: Haptics = NoHaptics
    var tips: TipsState? = null
    var cornerAtFullPx = 0f
    var sideShiftPx = 0f
    var landingCornerPx = 0f

    /** The overlay layer the landing artwork is drawn through, so its opacity is the card's own. */
    var artLayer: GraphicsLayer? = null

    /** The poster layer [artLayer] currently refers to; recording it again every frame buys nothing. */
    var artRecordedFrom: GraphicsLayer? = null

    /** A gesture has begun following. */
    var onStarted: () -> Unit = {}

    /** The page has gone (true) or come back whole (false); the host finishes its own back. */
    var onFinished: (committed: Boolean) -> Unit = {}

    /** Where the card would land now, or null to go back the ordinary way. */
    var resolveTarget: () -> ZoomBackTarget? = { null }

    var phase by mutableStateOf(ZoomBackPhase.Idle)
        private set

    /** The page the card is cut from, in pixels. */
    var page by mutableStateOf(Size.Zero)

    private var side by mutableStateOf(false)
    private var pivot by mutableStateOf(Offset.Zero)
    private var drag by mutableStateOf(Offset.Zero)
    private var progress by mutableFloatStateOf(0f)
    private var toward by mutableFloatStateOf(1f)
    private var lift by mutableFloatStateOf(0f)
    private var sideStartY = 0f
    private var armed = false

    // Fixed at release; read while the card settles.
    private var from = ZoomCard.resting(Size.Zero)
    private var releasedUnder = 0f
    private var landing: ZoomBackTarget? = null
    private val settle = Animatable(0f)
    private var job: Job? = null

    // The system's side swipe reports only how far it has got, never how fast. The card's own
    // edges are timed here instead — it mostly shrinks, so its centre alone says little — and the
    // flight it is let go into starts at the speed it had (MO5): commit and cancel used to fly off
    // a standing start.
    private val sideTopLeft = VelocityTracker()
    private val sideBottomRight = VelocityTracker()
    private var sideClock: TimeSource.Monotonic.ValueTimeMark? = null
    private var sideSampleMs = -1L

    /** The card as a finger caught it in flight (see [catchAt]); it follows that finger as it was. */
    private var caught: ZoomCard? = null
    private var caughtUnder = 0f

    /** Anything under way, including a finished page still waiting for its host to remove it. */
    val active: Boolean get() = phase != ZoomBackPhase.Idle

    /** Flying into its poster or home again: a finger can take hold of the card (see [catchAt]). */
    val catchable: Boolean get() = !still && (phase == ZoomBackPhase.Flying || phase == ZoomBackPhase.Returning)

    val idle: Boolean get() = phase == ZoomBackPhase.Idle

    val pullFollowing: Boolean get() = phase == ZoomBackPhase.Following && !side

    val sideFollowing: Boolean get() = phase == ZoomBackPhase.Following && side

    /** The artwork a landing card shows, while it lands. */
    val landingArt: ZoomBackArt? get() = landing?.art

    fun startPull(pivot: Offset): Boolean {
        if (!idle || page.width <= 0f || page.height <= 0f) return false
        side = false
        caught = null
        this.pivot = pivot
        drag = Offset.Zero
        progress = 0f
        armed = false
        phase = ZoomBackPhase.Following
        onStarted()
        return true
    }

    /**
     * A finger came down at [position] while the card flies (MO5). On the card, it stops there and
     * follows that finger as it was caught — its size, its corners, how much of the page and of the
     * poster it shows — until the finger lets go and the release decides again, from the speed it
     * lets go at and from how far back the page underneath had already come. True when caught.
     */
    fun catchAt(position: Offset): Boolean {
        if (!catchable) return false
        val now = card()
        if (!now.bounds.contains(position)) return false
        val under = underlay()
        job?.cancel()
        job = null
        caught = now
        caughtUnder = under
        side = false
        pivot = position
        drag = Offset.Zero
        progress = under
        armed = under >= ZOOM_BACK_COMMIT
        phase = ZoomBackPhase.Following
        return true
    }

    fun movePull(position: Offset) {
        if (!pullFollowing) return
        drag = position - pivot
        follow(
            if (caught != null) {
                zoomBackCaughtProgress(caughtUnder, drag.y, ZOOM_BACK_PULL_EXTENT * page.height)
            } else {
                zoomBackPullProgress(drag.y, page.height)
            },
        )
    }

    /** The finger let go, moving at [velocity] pixels a second. */
    fun releasePull(velocity: Offset) {
        if (!pullFollowing) return
        val extent = ZOOM_BACK_PULL_EXTENT * page.height
        // A caught card is judged from where the page underneath had got to, not from rest.
        val travelled = if (caught != null) caughtUnder * extent + drag.y else drag.y
        // The finger carries the card as one piece: its speed is the card's.
        val speed = { start: Rect, end: Rect -> zoomBackFlightVelocity(velocity, start, end) }
        if (zoomBackCommits(travelled, velocity.y, extent)) {
            tips?.markUsed(Tips.ZOOM_BACK)
            commit(speed)
        } else {
            cancel(speed)
        }
    }

    /** A second finger, or the stream taken away: the page goes home. */
    fun interruptPull() {
        if (pullFollowing) cancel { _, _ -> 0f }
    }

    /** The system's side swipe began; [toward] is +1 for a swipe from the left edge, −1 from the right. */
    fun startSide(
        toward: Float,
        touchY: Float,
        progress: Float,
    ): Boolean {
        if (!idle || page.width <= 0f || page.height <= 0f) return false
        side = true
        caught = null
        this.toward = toward
        sideStartY = touchY
        lift = 0f
        this.progress = 0f
        armed = false
        phase = ZoomBackPhase.Following
        follow(progress)
        sideTopLeft.resetTracking()
        sideBottomRight.resetTracking()
        sideClock = TimeSource.Monotonic.markNow()
        sideSampleMs = -1L
        trackSide()
        onStarted()
        return true
    }

    fun moveSide(
        touchY: Float,
        progress: Float,
    ) {
        if (!sideFollowing) return
        lift = touchY - sideStartY
        follow(progress)
        trackSide()
    }

    /** The system decided to go back: the platform's own commit rule, not ours. */
    fun commitSide() {
        if (sideFollowing) commit(sideReleaseSpeed())
    }

    fun cancelSide() {
        if (sideFollowing) cancel(sideReleaseSpeed())
    }

    /** Where the card's edges are now, and when, for [sideReleaseSpeed]. */
    private fun trackSide() {
        val clock = sideClock ?: return
        val now = clock.elapsedNow().inWholeMilliseconds
        if (now <= sideSampleMs) return
        sideSampleMs = now
        val bounds = card().bounds
        sideTopLeft.addPosition(now, bounds.topLeft)
        sideBottomRight.addPosition(now, bounds.bottomRight)
    }

    /**
     * The flight speed of a card let go by the system, from how fast its edges were moving. Sampled
     * once more at the release, so a swipe held still before it ends counts as having stopped.
     */
    private fun sideReleaseSpeed(): (Rect, Rect) -> Float {
        trackSide()
        val topLeft = sideTopLeft.calculateVelocity().let { Offset(it.x, it.y) }
        val bottomRight = sideBottomRight.calculateVelocity().let { Offset(it.x, it.y) }
        return { start, end -> zoomBackEdgeFlightVelocity(topLeft, bottomRight, start, end) }
    }

    /** The card as it should be drawn now. */
    fun card(page: Size = this.page): ZoomCard =
        when (phase) {
            ZoomBackPhase.Idle -> ZoomCard.resting(page)
            ZoomBackPhase.Following ->
                caught?.let { it.copy(bounds = it.bounds.translate(drag)) }
                    ?: if (side) {
                        zoomBackSideCard(page, progress, toward, lift, sideShiftPx, cornerAtFullPx)
                    } else {
                        zoomBackPullCard(page, pivot, drag, progress, cornerAtFullPx)
                    }
            ZoomBackPhase.Flying ->
                landing?.let { target ->
                    zoomBackFlightCard(from, target.bounds, page, settle.value, landingCornerPx, landing = true)
                        .continuingFrom(from, settle.value, landing = true)
                } ?: from
            ZoomBackPhase.Returning ->
                zoomBackFlightCard(from, Rect(Offset.Zero, page), page, settle.value, 0f, landing = false)
                    .continuingFrom(from, settle.value, landing = false)
            ZoomBackPhase.Fading -> zoomBackFadeCard(from, settle.value, shrink = !still)
            ZoomBackPhase.Done -> from.copy(contentAlpha = 0f, artAlpha = 0f)
        }

    /** How far the page underneath has come back: 0 while the page above is whole, 1 once it has gone. */
    fun underlay(): Float =
        when (phase) {
            ZoomBackPhase.Idle, ZoomBackPhase.Done -> 1f
            ZoomBackPhase.Following -> progress.coerceIn(0f, 1f)
            ZoomBackPhase.Flying, ZoomBackPhase.Fading ->
                releasedUnder + (1f - releasedUnder) * settle.value.coerceIn(0f, 1f)
            ZoomBackPhase.Returning -> releasedUnder * (1f - settle.value.coerceIn(0f, 1f))
        }

    /** Back to rest without telling anyone: the host has already taken the page away, or kept it. */
    fun reset() {
        job?.cancel()
        job = null
        landing?.art?.hidden = false
        landing = null
        caught = null
        progress = 0f
        drag = Offset.Zero
        lift = 0f
        phase = ZoomBackPhase.Idle
    }

    private fun follow(next: Float) {
        progress = next
        // 越过 30% ticks; falling back below it releases. Landing, later, is silent.
        val past = next >= ZOOM_BACK_COMMIT
        if (past != armed) {
            haptics.play(if (past) HapticSignal.Threshold else HapticSignal.ThresholdRelease)
            armed = past
        }
    }

    /** [speed] turns how the card was moving into flights a second along the way it is sent. */
    private fun commit(speed: (Rect, Rect) -> Float) {
        val start = card()
        val target = if (still) null else resolveTarget()
        from = start
        releasedUnder = underlay()
        // A card caught on its way into one poster lets that poster show again if it now goes
        // elsewhere; the same poster stays hidden, or it would show twice for a frame.
        if (landing?.art !== target?.art) landing?.art?.hidden = false
        landing = target
        caught = null
        artRecordedFrom = null
        phase = if (target != null) ZoomBackPhase.Flying else ZoomBackPhase.Fading
        job?.cancel()
        job =
            scope.launch {
                settle.snapTo(0f)
                if (target != null) {
                    target.art?.hidden = true
                    settle.animateTo(
                        targetValue = 1f,
                        // To the pixel: the card vanishes on landing, and a last stretch skipped in
                        // one frame showed as a jump onto the poster.
                        animationSpec = Motion.oneTake(Motion.ONE_TAKE_PROGRESS_THRESHOLD),
                        initialVelocity = speed(start.bounds, target.bounds),
                    )
                } else {
                    settle.animateTo(1f, Motion.tween(if (still) Motion.REDUCED_FADE else Motion.POP))
                }
                // The card is drawn no more and the poster it landed on shows itself again, in
                // the same frame: the two are the same picture in the same place.
                phase = ZoomBackPhase.Done
                target?.art?.hidden = false
                onFinished(true)
                // A host that never takes the page away must not leave it invisible.
                delay(ZOOM_BACK_DONE_TIMEOUT_MS)
                if (phase == ZoomBackPhase.Done) reset()
            }
    }

    private fun cancel(speed: (Rect, Rect) -> Float) {
        val start = card()
        from = start
        releasedUnder = underlay()
        // A card caught mid-landing already shows some of the poster: that art fades back out
        // over it on the way home, while the poster itself shows again in its place.
        landing?.art?.hidden = false
        if (start.artAlpha <= 0f) landing = null
        caught = null
        phase = ZoomBackPhase.Returning
        job?.cancel()
        job =
            scope.launch {
                settle.snapTo(0f)
                if (still) {
                    settle.animateTo(1f, Motion.tween(Motion.REDUCED_FADE))
                } else {
                    settle.animateTo(
                        targetValue = 1f,
                        animationSpec = Motion.oneTake(Motion.ONE_TAKE_PROGRESS_THRESHOLD),
                        initialVelocity = speed(start.bounds, Rect(Offset.Zero, page)),
                    )
                }
                reset()
                onFinished(false)
            }
    }
}

/**
 * A card let go of again after a finger caught it mid-landing keeps what it already showed. Flying
 * on into a poster, the poster's art goes on from where it was instead of starting over; flying
 * home, that art fades back out as the page's own content comes back whole. A card that was never
 * caught — the page whole, no art — is left exactly as it was.
 */
internal fun ZoomCard.continuingFrom(
    from: ZoomCard,
    fraction: Float,
    landing: Boolean,
): ZoomCard {
    if (landing) return copy(artAlpha = maxOf(artAlpha, from.artAlpha))
    val fade = (fraction / ZOOM_BACK_CONTENT_FADE).coerceIn(0f, 1f)
    return copy(
        contentAlpha = from.contentAlpha + (1f - from.contentAlpha) * fade,
        artAlpha = from.artAlpha * (1f - fade),
    )
}

/**
 * How far back the page underneath has come while a finger holds a caught card: where it had got to
 * when the card was caught, [under], and [dy] of pull since then over the [extent] a pull spans.
 */
internal fun zoomBackCaughtProgress(
    under: Float,
    dy: Float,
    extent: Float,
): Float = if (extent > 0f) (under + dy / extent).coerceAtLeast(0f) else under

/**
 * The flight's starting speed, in flights a second, for a card whose top-left and bottom-right
 * corners were moving at [topLeft] and [bottomRight] pixels a second as it left [from] for [to]:
 * the card's motion along the way it is sent. Unlike [zoomBackFlightVelocity] this sees a card that
 * is shrinking, not only one that is moving — the system's side swipe mostly shrinks the page.
 */
internal fun zoomBackEdgeFlightVelocity(
    topLeft: Offset,
    bottomRight: Offset,
    from: Rect,
    to: Rect,
): Float {
    val left = to.left - from.left
    val top = to.top - from.top
    val right = to.right - from.right
    val bottom = to.bottom - from.bottom
    val squared = left * left + top * top + right * right + bottom * bottom
    val moving = listOf(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    if (squared < 1f || moving.any { !it.isFinite() }) return 0f
    val along = (topLeft.x * left + topLeft.y * top + bottomRight.x * right + bottomRight.y * bottom) / squared
    return along.coerceIn(-ZOOM_BACK_MAX_FLIGHT_VELOCITY, ZOOM_BACK_MAX_FLIGHT_VELOCITY)
}

internal enum class ZoomBackPhase { Idle, Following, Flying, Fading, Returning, Done }

/** How long a finished page may wait for its host to take it away before it is drawn again. */
private const val ZOOM_BACK_DONE_TIMEOUT_MS = 1_000L

/** Where a card lands, in the page's coordinates, and what it shows as it arrives. */
internal class ZoomBackTarget(
    val bounds: Rect,
    val art: ZoomBackArt?,
)

/**
 * Something a landing card can draw: the poster's artwork, recorded into a layer by the poster
 * itself while a zoom back wants it. [hidden] keeps the original off screen while the card is on
 * its way to it, so the card lands in an empty slot rather than on top of a copy of itself.
 */
@Stable
internal class ZoomBackArt {
    var layer: GraphicsLayer? = null
    var recorded = false
    var hidden by mutableStateOf(false)
}

/** What a page is to the gesture going on: the card itself, the page it reveals, or neither. */
internal enum class ZoomBackRole { None, Page, Underlay }

/**
 * Draws [controller]'s gesture on this node: the shade behind the card and the card itself for
 * [ZoomBackRole.Page], the returning scale for [ZoomBackRole.Underlay]. [role] is read while drawing,
 * so a gesture moves layers only; nothing recomposes. [onInner] receives the coordinates inside the
 * transform, which is where a poster's final place is measured from.
 */
internal fun Modifier.zoomBackLayers(
    controller: ZoomBackController,
    role: () -> ZoomBackRole,
    onInner: (LayoutCoordinates) -> Unit = {},
): Modifier =
    this
        .drawBehind {
            if (role() == ZoomBackRole.Page) {
                val dim = zoomBackUnderlayDim(controller.underlay())
                if (dim > 0f && controller.phase != ZoomBackPhase.Done) drawRect(Color.Black.copy(alpha = dim))
            }
        }.graphicsLayer {
            when (role()) {
                ZoomBackRole.Page -> {
                    val card = controller.card(size)
                    if (controller.phase == ZoomBackPhase.Done) {
                        alpha = 0f
                    } else {
                        val moved = card.translation(size)
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = card.contentScale
                        scaleY = card.contentScale
                        translationX = moved.x
                        translationY = moved.y
                        shape = ZoomCardShape(card.localBounds(size), card.cornerRadius / card.contentScale)
                        clip = true
                    }
                }
                ZoomBackRole.Underlay -> {
                    val scale = zoomBackUnderlayScale(controller.underlay())
                    scaleX = scale
                    scaleY = scale
                }
                ZoomBackRole.None -> Unit
            }
        }.onPlaced(onInner)
        .drawWithContent {
            drawContent()
            if (role() == ZoomBackRole.Page) drawLandingArt(controller)
        }.graphicsLayer {
            if (role() == ZoomBackRole.Page) alpha = controller.card(size).contentAlpha
        }

/** The poster's artwork over the landing card, filling the part of the page the card still shows. */
private fun ContentDrawScope.drawLandingArt(controller: ZoomBackController) {
    val card = controller.card(size)
    if (card.artAlpha <= 0f) return
    val art = controller.landingArt ?: return
    val source = art.layer?.takeIf { art.recorded } ?: return
    val overlay = controller.artLayer ?: return
    val artSize = source.size
    if (artSize.width <= 0 || artSize.height <= 0) return
    // Recorded once per landing: the overlay only references the poster's own live layer, so it
    // follows whatever the poster draws. Its opacity is the overlay's own, never the poster's.
    if (controller.artRecordedFrom !== source) {
        overlay.record(size = artSize) { drawLayer(source) }
        controller.artRecordedFrom = source
    }
    overlay.alpha = card.artAlpha
    val visible = card.localBounds(size)
    val scale = max(visible.width / artSize.width, visible.height / artSize.height)
    clipRect(visible.left, visible.top, visible.right, visible.bottom) {
        withTransform({
            translate(
                visible.center.x - artSize.width * scale / 2f,
                visible.center.y - artSize.height * scale / 2f,
            )
            scale(scale, scale, Offset.Zero)
        }) { drawLayer(overlay) }
    }
}

/** The card's outline in the unscaled page: [bounds] with [radius] corners. */
private data class ZoomCardShape(
    val bounds: Rect,
    val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(RoundRect(bounds, CornerRadius(radius, radius)))
}

/**
 * The pull-down: watches the finger from outside the page and takes over when the page's own
 * scrolling has nothing left to give — the list is at its top — and the drag has been decided
 * vertical (|dy| > 0.8 |dx| after 8 dp). A finger landing on a card still in flight catches it. A
 * horizontal drag belongs to whatever shelf it began on; a second finger sends a pull home. Until
 * [canStart] says yes and [onStart] starts it, this only listens. With [swallowLeftover] a downward
 * drag the page cannot scroll goes no further up, so a page drawn over another never pulls the one
 * beneath it.
 */
@Composable
internal fun Modifier.zoomBackPull(
    controller: ZoomBackController,
    canStart: () -> Boolean,
    onStart: (Offset) -> Unit,
    swallowLeftover: Boolean = false,
): Modifier {
    val tracker = remember { PullTracker() }
    val latestCanStart by rememberUpdatedState(canStart)
    val latestOnStart by rememberUpdatedState(onStart)
    val latestSwallow by rememberUpdatedState(swallowLeftover)
    val connection =
        remember(controller, tracker) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (tracker.pulled && controller.pullFollowing) available else Offset.Zero

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (tracker.pulled && controller.pullFollowing) return available
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    val leftover = if (latestSwallow) Offset(0f, available.y) else Offset.Zero
                    if (!tracker.pressed || tracker.multiTouch || tracker.pulled) return leftover
                    if (tracker.axisNow() != DragAxis.Vertical || !latestCanStart()) return leftover
                    latestOnStart(tracker.current)
                    if (!controller.pullFollowing) return leftover
                    tracker.pulled = true
                    return Offset(0f, available.y)
                }

                // The list lets go with a fling of its own; a pull already decided what the release means.
                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (tracker.pulled) available else Velocity.Zero
            }
        }
    return this
        .pointerInput(controller, tracker) {
            val slop = ZoomBackSlop.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                tracker.start(down.position, down.uptimeMillis)
                // A card in flight is caught by the finger that lands on it (MO5), and the whole
                // gesture is the card's: nothing under it scrolls or takes a tap meanwhile.
                val caught = controller.catchAt(down.position)
                if (caught) {
                    tracker.pulled = true
                    down.consume()
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (caught) event.changes.forEach { it.consume() }
                        if (!tracker.multiTouch && event.changes.count { it.pressed } > 1) {
                            tracker.multiTouch = true
                            controller.interruptPull()
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            tracker.velocity.addPosition(change.uptimeMillis, change.position)
                            if (tracker.pulled && controller.pullFollowing) {
                                val velocity = tracker.velocity.calculateVelocity()
                                controller.releasePull(Offset(velocity.x, velocity.y))
                            }
                            break
                        }
                        tracker.move(change.position, change.uptimeMillis, slop)
                        if (tracker.pulled) controller.movePull(change.position)
                    }
                } finally {
                    tracker.pressed = false
                    if (tracker.pulled) controller.interruptPull()
                }
            }
        }.nestedScroll(connection)
}

/** One finger's way across the page: where it began, where it is, and which way it has gone. */
private class PullTracker {
    var down = Offset.Zero
    var current = Offset.Zero
    var pressed = false
    var multiTouch = false
    var axis = DragAxis.Undecided

    /** This gesture became a pull; its release and its fling are the pull's. */
    var pulled = false

    val velocity = VelocityTracker()

    fun start(
        position: Offset,
        timeMillis: Long,
    ) {
        down = position
        current = position
        pressed = true
        multiTouch = false
        axis = DragAxis.Undecided
        pulled = false
        velocity.resetTracking()
        velocity.addPosition(timeMillis, position)
    }

    fun move(
        position: Offset,
        timeMillis: Long,
        slop: Float,
    ) {
        current = position
        velocity.addPosition(timeMillis, position)
        if (axis == DragAxis.Undecided) {
            axis = resolveDragAxis(position.x - down.x, position.y - down.y, slop, ZOOM_BACK_HORIZONTAL_BIAS)
        }
    }

    /** The decided axis, or — when the list began scrolling before 8 dp — the one the travel so far points to. */
    fun axisNow(): DragAxis =
        if (axis != DragAxis.Undecided) {
            axis
        } else {
            resolveDragAxis(current.x - down.x, current.y - down.y, 0f, ZOOM_BACK_HORIZONTAL_BIAS)
        }
}

// ---------------------------------------------------------------- sources

/** A poster a page came from, as the page underneath lays it out while a zoom back wants it. */
internal class ZoomBackSource(
    val key: MediaSharedElementKey,
    val entry: String,
) {
    var coordinates: LayoutCoordinates? = null
    val art = ZoomBackArt()
}

/**
 * The posters of one navigation host that a zoom back could land on. Nothing registers until a
 * gesture names a key in [wanted]; then only the posters with that key do, each with the route
 * it belongs to, so the page being pulled — whose hero shares the key — never counts.
 */
@Stable
internal class ZoomBackSources {
    var wanted by mutableStateOf<MediaSharedElementKey?>(null)
    private val live = mutableListOf<ZoomBackSource>()

    fun add(source: ZoomBackSource) {
        live += source
    }

    fun remove(source: ZoomBackSource) {
        live -= source
    }

    /** The one attached poster with [key] on [entry]; none, or more than one, means there is no place to go. */
    fun single(
        key: MediaSharedElementKey,
        entry: String,
    ): ZoomBackSource? =
        live
            .filter { it.key == key && it.entry == entry && it.coordinates?.isAttached == true }
            .singleOrNull()
}

internal val LocalZoomBackSources = staticCompositionLocalOf<ZoomBackSources?> { null }

/** The route a node belongs to, inside a navigation host that can zoom back. */
internal val LocalZoomBackEntry = staticCompositionLocalOf<String?> { null }

/**
 * Lets a zoom back land on this poster's artwork: while a gesture wants [key], the artwork records
 * itself for the landing card, reports where it is, and steps aside while the card flies to it.
 * Otherwise this adds nothing — posters in a scrolling grid pay for none of it.
 */
@Composable
internal fun Modifier.zoomBackSource(key: MediaSharedElementKey?): Modifier {
    val sources = LocalZoomBackSources.current ?: return this
    val entry = LocalZoomBackEntry.current ?: return this
    if (key == null) return this
    val wanted by remember(sources, key) { derivedStateOf { sources.wanted == key } }
    if (!wanted) return this
    val layer = rememberGraphicsLayer()
    val source = remember(sources, key, entry, layer) { ZoomBackSource(key, entry).also { it.art.layer = layer } }
    DisposableEffect(sources, source) {
        sources.add(source)
        onDispose { sources.remove(source) }
    }
    return this
        .onPlaced { source.coordinates = it }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            source.art.recorded = true
            if (!source.art.hidden) drawLayer(layer)
        }
}

/**
 * Where an overlay page opened from — the shelf 查看全部 expanded, the rail 全部剧集 came out of — so
 * pulling the page down can put it back there. Attach it with [zoomBackAnchor] and hand it to
 * [OverlayPage].
 */
@Stable
class ZoomBackAnchor {
    internal var coordinates: LayoutCoordinates? = null
    internal val art = ZoomBackArt()
    internal var wanted by mutableStateOf(false)
}

/** Marks this node as the place an [OverlayPage] given [anchor] goes back into. */
@Composable
fun Modifier.zoomBackAnchor(anchor: ZoomBackAnchor?): Modifier {
    if (anchor == null) return this
    val layer = rememberGraphicsLayer()
    anchor.art.layer = layer
    return this
        .onPlaced { anchor.coordinates = it }
        .drawWithContent {
            if (anchor.wanted) {
                layer.record { this@drawWithContent.drawContent() }
                anchor.art.recorded = true
                if (!anchor.art.hidden) drawLayer(layer)
            } else {
                drawContent()
            }
        }
}
