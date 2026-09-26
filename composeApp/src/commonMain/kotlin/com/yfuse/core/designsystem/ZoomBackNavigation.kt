package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

// ---------------------------------------------------------------- 跟手返回 in a navigation stack
//
// NavDisplay composes the page underneath only while *its* back gesture is in progress, and it
// finishes that gesture the moment the platform says so. A zoom back needs the page underneath
// from the first pixel of a pull, and needs the page on top to stay until its flight has landed.
// So a stack that can zoom back gives NavDisplay a dispatcher of its own, which only this file
// drives, and stands in for NavDisplay's handler on the activity's dispatcher: an ordinary page's
// system swipe is passed straight through, event for event; a page entered from a poster follows
// the zoom geometry and tells NavDisplay the gesture is over only when the card has gone.

/** A back handler whose four callbacks are plain lambdas. */
internal class RelayBackHandler : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
    var onStarted: (NavigationEvent) -> Unit = {}
    var onProgressed: (NavigationEvent) -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onCancelled: () -> Unit = {}

    override fun onBackStarted(event: NavigationEvent) {
        onStarted(event)
    }

    override fun onBackProgressed(event: NavigationEvent) {
        onProgressed(event)
    }

    override fun onBackCompleted() {
        onCompleted()
    }

    override fun onBackCancelled() {
        onCancelled()
    }
}

/** The dispatcher NavDisplay listens to inside a zoom-back stack, and the only input it has. */
internal class PrivateNavigation {
    private val dispatcher = NavigationEventDispatcher()
    private val input = DirectNavigationEventInput()

    var inProgress = false
        private set

    val owner: NavigationEventDispatcherOwner =
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher: NavigationEventDispatcher
                get() = dispatcher
        }

    fun attach() {
        dispatcher.addInput(input)
    }

    fun dispose() {
        runCatching { dispatcher.removeInput(input) }
        runCatching { dispatcher.dispose() }
    }

    fun started(event: NavigationEvent) {
        input.backStarted(event)
        inProgress = true
    }

    fun progressed(event: NavigationEvent) {
        if (inProgress) input.backProgressed(event)
    }

    fun completed() {
        input.backCompleted()
        inProgress = false
    }

    fun cancelled() {
        if (inProgress) input.backCancelled()
        inProgress = false
    }
}

/** Which poster a route was opened from, and what it was opened over. */
internal class ZoomOrigin(
    val key: MediaSharedElementKey,
    val underlay: String,
    /** The page's size when it was opened; a different one since means the screen has turned. */
    val pageSize: Size,
)

/** Where a route's page sits: outside its zoom layers, and inside them. */
internal class ZoomFrame {
    var outer: LayoutCoordinates? = null
    var inner: LayoutCoordinates? = null

    val size: Size get() = outer?.takeIf { it.isAttached }?.size?.toSize() ?: Size.Zero
}

private enum class SystemRoute { None, Forward, Zoom, Ignored }

/** +1 for a swipe from the left edge, towards which the page leans; −1 from the right. */
internal fun zoomBackToward(swipeEdge: Int): Float = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f

/** NavDisplay's own transition while a zoom back draws the pages: nothing, for no time at all. */
internal fun zoomBackRouteTransform(): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        targetContentZIndex = -1f,
        sizeTransform = null,
    )

/** One navigation stack's 跟手返回: its gesture, its origins, and the stand-in for NavDisplay's back. */
@Stable
internal class ZoomBackNavHost(
    val controller: ZoomBackController,
    private val realOwner: NavigationEventDispatcherOwner,
) {
    private val navigation = PrivateNavigation()
    private val frames = mutableMapOf<String, ZoomFrame>()
    private var stack: List<String> = emptyList()
    private var system = SystemRoute.None

    val sources = ZoomBackSources()

    /** Routes entered from a poster, by content key. */
    val origins = mutableStateMapOf<String, ZoomOrigin>()

    /** Stands in for NavDisplay's handler on the activity's dispatcher. */
    val standIn = RelayBackHandler()

    var pageKey by mutableStateOf<String?>(null)
        private set
    var underlayKey by mutableStateOf<String?>(null)
        private set

    /** Pops the stack for a back that never previewed — the three-button bar, a hardware key. */
    var onBack: () -> Unit = {}

    /** Something else has the finger: a lift menu is up, a screen reader is on. */
    var blocked: () -> Boolean = { false }

    /** What NavDisplay is given in place of the activity's dispatcher. */
    val displayOwner: NavigationEventDispatcherOwner get() = navigation.owner

    /** What the pages get back, so their own handlers still hear the system. */
    val pageOwner: NavigationEventDispatcherOwner get() = realOwner

    init {
        controller.onFinished = { committed ->
            if (committed) {
                // NavDisplay pops now; the page is invisible until it has gone. See [onStack].
                navigation.completed()
            } else {
                navigation.cancelled()
                clearGesture()
            }
        }
        controller.resolveTarget = { resolveTarget() }
        standIn.onStarted = { systemStarted(it) }
        standIn.onProgressed = { systemProgressed(it) }
        standIn.onCompleted = { systemCompleted() }
        standIn.onCancelled = { systemCancelled() }
    }

    fun attach() {
        navigation.attach()
        realOwner.navigationEventDispatcher.addHandler(standIn)
    }

    fun detach() {
        standIn.remove()
        navigation.dispose()
    }

    fun frame(key: String): ZoomFrame = frames.getOrPut(key) { ZoomFrame() }

    fun forget(key: String) {
        frames.remove(key)
    }

    fun isZoomPage(key: String): Boolean = origins.containsKey(key)

    fun roleOf(key: String): ZoomBackRole =
        when {
            !controller.active -> ZoomBackRole.None
            key == pageKey -> ZoomBackRole.Page
            key == underlayKey -> ZoomBackRole.Underlay
            else -> ZoomBackRole.None
        }

    /**
     * The stack as it now is. A route pushed right after a poster was tapped remembers that
     * poster; routes that have left forget theirs; a page that left in the middle of its own
     * gesture takes the gesture with it.
     */
    fun onStack(
        keys: List<String>,
        pushedFrom: MediaSharedElementKey?,
    ) {
        if (pushedFrom != null && keys.size >= 2) {
            val underlay = keys[keys.size - 2]
            origins[keys.last()] = ZoomOrigin(pushedFrom, underlay, frames[underlay]?.size ?: Size.Zero)
        }
        origins.keys.filter { it !in keys }.forEach { origins.remove(it) }
        val page = pageKey
        if (page != null && (page !in keys || keys.last() != page)) {
            if (controller.phase != ZoomBackPhase.Done) navigation.cancelled()
            controller.reset()
            clearGesture()
        }
        stack = keys
    }

    fun canPull(key: String): Boolean =
        controller.idle &&
            system == SystemRoute.None &&
            stack.size > 1 &&
            stack.last() == key &&
            origins.containsKey(key) &&
            !blocked()

    fun startPull(
        key: String,
        pivot: Offset,
    ) {
        if (!canPull(key) || !beginGesture(key)) return
        if (controller.startPull(pivot)) {
            navigation.started(
                NavigationEvent(
                    swipeEdge = NavigationEvent.EDGE_NONE,
                    progress = 0f,
                    touchX = pivot.x.coerceAtLeast(0f),
                    touchY = pivot.y.coerceAtLeast(0f),
                ),
            )
        } else {
            clearGesture()
        }
    }

    private fun systemStarted(event: NavigationEvent) {
        val top = stack.lastOrNull()
        system =
            when {
                !controller.idle -> SystemRoute.Ignored
                top != null && stack.size > 1 && origins.containsKey(top) && !blocked() && beginGesture(top) -> {
                    if (controller.startSide(zoomBackToward(event.swipeEdge), event.touchY, event.progress)) {
                        navigation.started(
                            NavigationEvent(
                                swipeEdge = event.swipeEdge,
                                progress = 0f,
                                touchX = event.touchX,
                                touchY = event.touchY,
                            ),
                        )
                        SystemRoute.Zoom
                    } else {
                        clearGesture()
                        navigation.started(event)
                        SystemRoute.Forward
                    }
                }
                else -> {
                    navigation.started(event)
                    SystemRoute.Forward
                }
            }
    }

    private fun systemProgressed(event: NavigationEvent) {
        when (system) {
            SystemRoute.Zoom -> controller.moveSide(event.touchY, event.progress)
            SystemRoute.Forward -> navigation.progressed(event)
            SystemRoute.None, SystemRoute.Ignored -> Unit
        }
    }

    private fun systemCompleted() {
        when (system) {
            SystemRoute.Zoom -> controller.commitSide()
            SystemRoute.Forward -> navigation.completed()
            SystemRoute.Ignored -> Unit
            // A back with no preview: what NavDisplay's own handler would have done.
            SystemRoute.None -> if (controller.idle) onBack()
        }
        system = SystemRoute.None
    }

    private fun systemCancelled() {
        when (system) {
            SystemRoute.Zoom -> controller.cancelSide()
            SystemRoute.Forward -> navigation.cancelled()
            SystemRoute.None, SystemRoute.Ignored -> Unit
        }
        system = SystemRoute.None
    }

    private fun beginGesture(key: String): Boolean {
        val underlay = stack.getOrNull(stack.size - 2) ?: return false
        val size = frames[key]?.size ?: return false
        if (size.width <= 0f || size.height <= 0f) return false
        controller.page = size
        pageKey = key
        underlayKey = underlay
        sources.wanted = origins[key]?.key
        return true
    }

    private fun clearGesture() {
        pageKey = null
        underlayKey = null
        sources.wanted = null
    }

    /**
     * The poster the page came from, where it is now (09-24 审查 P2-34: re-resolved on the way
     * back, never a snapshot from the way in). None when the page underneath is not the one it was
     * opened over, the screen has turned since, the poster is not exactly one attached tile, or
     * less than half of it is on screen.
     */
    private fun resolveTarget(): ZoomBackTarget? {
        val page = pageKey ?: return null
        val underlay = underlayKey ?: return null
        val origin = origins[page] ?: return null
        if (origin.underlay != underlay) return null
        val pageOuter = frames[page]?.outer?.takeIf { it.isAttached } ?: return null
        val underFrame = frames[underlay] ?: return null
        val underOuter = underFrame.outer?.takeIf { it.isAttached } ?: return null
        val underInner = underFrame.inner?.takeIf { it.isAttached } ?: return null
        val pageSize = pageOuter.size.toSize()
        if (origin.pageSize != Size.Zero && origin.pageSize != pageSize) return null
        val source = sources.single(origin.key, underlay) ?: return null
        val poster = source.coordinates?.takeIf { it.isAttached } ?: return null
        // Measured inside the returning page's scale, so this is where the poster will be once
        // that page is whole again — where the card has to end up.
        val full = underInner.localBoundingBoxOf(poster, clipBounds = false)
        val shown = underInner.localBoundingBoxOf(poster, clipBounds = true)
        if (!zoomBackTargetVisible(full, shown, Rect(Offset.Zero, underInner.size.toSize()))) return null
        val offset = pageOuter.localPositionOf(underOuter, Offset.Zero)
        return ZoomBackTarget(full.translate(offset), source.art.takeIf { it.recorded })
    }
}

/**
 * The zoom back of a navigation stack, or null where it cannot have one: [enabled] only for
 * pushed-route stacks, and only where the activity provides a navigation-event dispatcher.
 * Decided once, so the host's composition never changes shape under its pages.
 */
@Composable
internal fun rememberZoomBackNavHost(enabled: Boolean): ZoomBackNavHost? {
    val realOwner = LocalNavigationEventDispatcherOwner.current
    val bridged = remember { enabled && realOwner != null }
    if (!bridged || realOwner == null) return null
    val scope = rememberCoroutineScope()
    val host = remember(realOwner) { ZoomBackNavHost(ZoomBackController(scope), realOwner) }
    DisposableEffect(host) {
        host.attach()
        onDispose { host.detach() }
    }
    ConfigureZoomBack(host.controller)
    return host
}

/** Hands a controller what it reads from the composition: motion, haptics, tips, sizes, its overlay layer. */
@Composable
private fun ConfigureZoomBack(controller: ZoomBackController) {
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val tips = LocalTips.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val artLayer = rememberGraphicsLayer()
    SideEffect {
        controller.still = still
        controller.haptics = haptics
        controller.tips = tips
        controller.artLayer = artLayer
        with(density) {
            controller.cornerAtFullPx = ZoomBackCorner.toPx()
            controller.sideShiftPx = ZoomBackSideShift.toPx()
            controller.landingCornerPx = Dimens.medium.toPx()
        }
    }
}

/** What NavDisplay is given: the host's own dispatcher, in place of the activity's. */
internal fun zoomBackDisplayLocals(host: ZoomBackNavHost?): Array<ProvidedValue<*>> =
    if (host == null) {
        emptyArray<ProvidedValue<*>>()
    } else {
        arrayOf<ProvidedValue<*>>(LocalNavigationEventDispatcherOwner provides host.displayOwner)
    }

/** What each route is given: the activity's dispatcher back, its posters, and which route it is. */
internal fun zoomBackRouteLocals(
    host: ZoomBackNavHost?,
    key: String,
): Array<ProvidedValue<*>> =
    if (host == null) {
        emptyArray<ProvidedValue<*>>()
    } else {
        arrayOf<ProvidedValue<*>>(
            LocalNavigationEventDispatcherOwner provides host.pageOwner,
            LocalZoomBackSources provides host.sources,
            LocalZoomBackEntry provides key,
        )
    }

/** Records where a route's page sits, from outside every layer the route draws through. */
internal fun Modifier.zoomBackFrame(
    host: ZoomBackNavHost?,
    key: String,
): Modifier = if (host == null) this else onPlaced { host.frame(key).outer = it }

/**
 * A route in a zoom-back stack: the pull-down when the route came from a poster, and the card, the
 * shade and the returning page for whichever gesture is going on. [settled] says the route's own
 * push has finished; a pull never starts on a page still arriving.
 */
@Composable
internal fun Modifier.zoomBackRoute(
    host: ZoomBackNavHost?,
    key: String,
    settled: () -> Boolean,
): Modifier {
    if (host == null) return this
    DisposableEffect(host, key) {
        onDispose { host.forget(key) }
    }
    val pull =
        if (host.isZoomPage(key)) {
            Modifier.zoomBackPull(
                controller = host.controller,
                canStart = { settled() && host.canPull(key) },
                onStart = { host.startPull(key, it) },
            )
        } else {
            Modifier
        }
    return this
        .then(pull)
        .zoomBackLayers(host.controller, role = { host.roleOf(key) }, onInner = { host.frame(key).inner = it })
}

/** 情境提示 for the pull-down, on a page that came from a poster, once it is the page in front. */
@Composable
internal fun BoxScope.ZoomBackRouteTip(
    host: ZoomBackNavHost?,
    key: String,
) {
    if (host == null || !host.isZoomPage(key)) return
    ContextualTip(
        id = Tips.ZOOM_BACK,
        text = "在顶部下拉可以缩回海报",
        active = LocalRouteVisible.current && host.controller.idle,
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = ZoomBackTipTop),
    )
}

/** Clear of a page's own top bar. */
private val ZoomBackTipTop = 60.dp

/**
 * A back handler that reports the whole predictive gesture — edge, finger and progress — not just
 * its progress. Where there is no navigation-event dispatcher it falls back to a plain back.
 */
@Composable
internal fun ZoomBackSystemBack(
    enabled: Boolean,
    onStarted: (NavigationEvent) -> Unit,
    onProgressed: (NavigationEvent) -> Unit,
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
) {
    val owner = LocalNavigationEventDispatcherOwner.current
    if (owner == null) {
        PlatformBackHandler(enabled = enabled, onBack = onCompleted)
        return
    }
    val latestStarted by rememberUpdatedState(onStarted)
    val latestProgressed by rememberUpdatedState(onProgressed)
    val latestCompleted by rememberUpdatedState(onCompleted)
    val latestCancelled by rememberUpdatedState(onCancelled)
    val handler =
        remember {
            RelayBackHandler().apply {
                this.onStarted = { latestStarted(it) }
                this.onProgressed = { latestProgressed(it) }
                this.onCompleted = { latestCompleted() }
                this.onCancelled = { latestCancelled() }
            }
        }
    SideEffect { handler.isBackEnabled = enabled }
    DisposableEffect(owner, handler) {
        owner.navigationEventDispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
}

/**
 * A full-screen page drawn over another — 全部剧集, 查看全部 — that goes back the way a route from a
 * poster does: pulled down from its top, or swiped from the side, it follows the finger and flies
 * back into [source], the shelf or rail it was opened from. Without a [source] on screen it goes
 * the ordinary way. [onZoomedAway] hears that the page has already gone, before [onBack] closes it.
 */
@Composable
internal fun ZoomBackOverlay(
    visible: Boolean,
    source: ZoomBackAnchor?,
    onBack: () -> Unit,
    onZoomedAway: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (visible) ReportOverlayVisible()
    val scope = rememberCoroutineScope()
    val controller = remember { ZoomBackController(scope) }
    val frame = remember { ZoomFrame() }
    val latestBack by rememberUpdatedState(onBack)
    val latestAway by rememberUpdatedState(onZoomedAway)
    val latestSource by rememberUpdatedState(source)
    val latestVisible by rememberUpdatedState(visible)
    val screenReader = rememberScreenReaderActive()
    val latestScreenReader by rememberUpdatedState(screenReader)
    ConfigureZoomBack(controller)
    SideEffect {
        controller.onStarted = { latestSource?.wanted = true }
        controller.onFinished = { committed ->
            latestSource?.wanted = false
            if (committed) {
                latestAway()
                latestBack()
            }
        }
        controller.resolveTarget = { overlayTarget(frame, latestSource) }
    }
    // Whether the system swipe under way is ours to follow.
    val sideRouted = remember { booleanArrayOf(false) }
    ZoomBackSystemBack(
        enabled = visible,
        onStarted = { event ->
            sideRouted[0] =
                controller.idle &&
                controller.startSide(zoomBackToward(event.swipeEdge), event.touchY, event.progress)
        },
        onProgressed = { event -> if (sideRouted[0]) controller.moveSide(event.touchY, event.progress) },
        onCompleted = {
            when {
                sideRouted[0] -> controller.commitSide()
                controller.idle -> latestBack()
            }
            sideRouted[0] = false
        },
        onCancelled = {
            if (sideRouted[0]) controller.cancelSide()
            sideRouted[0] = false
        },
    )
    Box(
        modifier
            .onPlaced {
                frame.outer = it
                controller.page = it.size.toSize()
            }.fillMaxSize()
            .zoomBackPull(
                controller = controller,
                canStart = { latestVisible && !latestScreenReader && controller.idle },
                onStart = { controller.startPull(it) },
                swallowLeftover = true,
            ).zoomBackLayers(
                controller,
                role = { if (controller.active) ZoomBackRole.Page else ZoomBackRole.None },
            ),
        content = content,
    )
}

/** Where an overlay lands: its [anchor], measured from the overlay's own frame, when half of it shows. */
private fun overlayTarget(
    frame: ZoomFrame,
    anchor: ZoomBackAnchor?,
): ZoomBackTarget? {
    val place = anchor?.coordinates?.takeIf { it.isAttached } ?: return null
    val outer = frame.outer?.takeIf { it.isAttached } ?: return null
    val full = outer.localBoundingBoxOf(place, clipBounds = false)
    val shown = outer.localBoundingBoxOf(place, clipBounds = true)
    if (!zoomBackTargetVisible(full, shown, Rect(Offset.Zero, outer.size.toSize()))) return null
    return ZoomBackTarget(full, anchor.art.takeIf { it.recorded })
}
