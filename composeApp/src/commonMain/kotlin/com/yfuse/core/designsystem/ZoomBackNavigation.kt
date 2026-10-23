package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.yfuse.core.logging.AppLog

// ---------------------------------------------------------------- 跟手返回 in a navigation stack
//
// NavDisplay composes the page underneath only while *its* back gesture is in progress, and it
// finishes that gesture the moment the platform says so. A zoom back needs the page underneath
// from the first preview frame, and needs the page on top to stay until its slide has finished.
// So a stack that can zoom back gives NavDisplay a dispatcher of its own, which only this file
// drives, and stands in for NavDisplay's handler on the activity's dispatcher. Measured pages
// follow an opaque side slide. NavDisplay completes the navigation only after the departing page
// has gone; content drags never dismiss navigation pages.

/** A back handler whose four callbacks are plain lambdas. */
internal class RelayBackHandler : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
    var onStarted: (NavigationEvent) -> Unit = {}
    var onProgressed: (NavigationEvent) -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onCancelled: () -> Unit = {}
    var acceptsBack: () -> Boolean = { true }
    private var gestureAccepted: Boolean? = null

    /** Disabling a handler does not cancel the gesture the dispatcher already assigned to it. */
    fun rejectGesture() {
        if (gestureAccepted == true) {
            gestureAccepted = false
            onCancelled()
        }
    }

    public override fun onBackStarted(event: NavigationEvent) {
        gestureAccepted = acceptsBack()
        if (gestureAccepted == true) onStarted(event)
    }

    public override fun onBackProgressed(event: NavigationEvent) {
        if (!acceptsBack()) rejectGesture()
        if (gestureAccepted == true) onProgressed(event)
    }

    public override fun onBackCompleted() {
        val accepted = gestureAccepted
        gestureAccepted = null
        when {
            accepted != false && acceptsBack() -> onCompleted()
            accepted == true -> onCancelled()
        }
    }

    public override fun onBackCancelled() {
        val accepted = gestureAccepted
        gestureAccepted = null
        if (accepted != false) onCancelled()
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
        cancelled()
        runCatching { dispatcher.removeInput(input) }
        runCatching { dispatcher.dispose() }
    }

    fun started(event: NavigationEvent) {
        cancelled()
        inProgress = true
        input.backStarted(event)
    }

    fun progressed(event: NavigationEvent) {
        if (inProgress) input.backProgressed(event)
    }

    fun completed() {
        inProgress = false
        input.backCompleted()
    }

    fun cancelled() {
        val wasInProgress = inProgress
        inProgress = false
        if (wasInProgress) input.backCancelled()
    }
}

/** Which poster a route was opened from, and what it was opened over. */
internal class ZoomOrigin(
    val key: MediaSharedElementKey,
    val underlay: String,
    /** The page's size when it was opened; a different one since means the screen has turned. */
    val pageSize: Size,
)

/**
 * A stack's [ZoomBackNavHost.origins], kept with the tab's saved state. Only the tab on screen is
 * composed, so leaving a tab takes its stack's host with it while the stack itself stays: a detail
 * opened from a poster is still that detail over that shelf when the tab comes back, and the host
 * made for it then has to know. Routes that left the stack meanwhile are dropped by its first
 * [ZoomBackNavHost.onStack]; a screen turned meanwhile fails the landing's size check, and the page
 * fades the ordinary way.
 */
internal val ZoomOriginsSaver: Saver<SnapshotStateMap<String, ZoomOrigin>, Any> =
    listSaver<SnapshotStateMap<String, ZoomOrigin>, Any?>(
        save = { origins ->
            origins.flatMap { (route, origin) ->
                listOf<Any?>(
                    route,
                    origin.key.serverId,
                    origin.key.itemId,
                    origin.key.kind,
                    origin.underlay,
                    origin.pageSize.width,
                    origin.pageSize.height,
                )
            }
        },
        restore = { saved ->
            val origins = mutableStateMapOf<String, ZoomOrigin>()
            saved.chunked(ZOOM_ORIGIN_FIELDS).forEach { fields ->
                val route = fields.getOrNull(0) as? String ?: return@forEach
                val itemId = fields.getOrNull(2) as? String ?: return@forEach
                val kind = fields.getOrNull(3) as? String ?: return@forEach
                val underlay = fields.getOrNull(4) as? String ?: return@forEach
                val width = fields.getOrNull(5) as? Float ?: return@forEach
                val height = fields.getOrNull(6) as? Float ?: return@forEach
                val key = MediaSharedElementKey(fields[1] as? String, itemId, kind)
                origins[route] = ZoomOrigin(key, underlay, Size(width, height))
            }
            origins
        },
    )

private const val ZOOM_ORIGIN_FIELDS = 7

/** Where a route's page sits: outside its zoom layers, and inside them. */
internal class ZoomFrame {
    var outer: LayoutCoordinates? = null
    var inner: LayoutCoordinates? = null

    val size: Size get() = outer?.takeIf { it.isAttached }?.size?.toSize() ?: Size.Zero
}

private enum class SystemRoute { None, Forward, Zoom, Ignored }

/** Returning always moves the current page out to the right, regardless of the gesture's edge. */
internal const val BACK_NAVIGATION_TOWARD = 1f

/**
 * The gesture controller owns the geometry; NavDisplay keeps both surfaces fully opaque.
 * Keep a finite transition clock even though alpha does not change: None lets the outgoing
 * entry reach PostExit at zero preview progress, then strands it there when the gesture cancels.
 */
internal fun zoomBackRouteTransform(): ContentTransform =
    ContentTransform(
        targetContentEnter = fadeIn(Motion.tween(Motion.QUICK), initialAlpha = 1f),
        initialContentExit = fadeOut(Motion.tween(Motion.QUICK), targetAlpha = 1f),
        targetContentZIndex = -1f,
        sizeTransform = null,
    )

/** One navigation stack's 跟手返回: its gesture, its origins, and the stand-in for NavDisplay's back. */
@Stable
internal class ZoomBackNavHost(
    val controller: ZoomBackController,
    private val realOwner: NavigationEventDispatcherOwner,
    /** Routes entered from a poster, by content key; kept with the stack, see [ZoomOriginsSaver]. */
    val origins: SnapshotStateMap<String, ZoomOrigin>,
) {
    private val navigation = PrivateNavigation()
    private val frames = mutableMapOf<String, ZoomFrame>()
    private var stack: List<String> = emptyList()
    private var system = SystemRoute.None
    private var systemStack: List<String>? = null
    private var visible = true

    val sources = ZoomBackSources()

    // NavDisplay can retain an outgoing entry after its key leaves the back stack. Its
    // last frame must stay hidden even after the gesture controller is reused/reset.
    private val retiredPages = mutableStateMapOf<String, Unit>()
    val suppressPopTransition: Boolean get() = controller.active || retiredPages.isNotEmpty()

    fun isRetired(key: String): Boolean = retiredPages.containsKey(key)

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
    var detailPreview = false
    var onSideStarted: () -> Unit = {}

    /** What NavDisplay is given in place of the activity's dispatcher. */
    val displayOwner: NavigationEventDispatcherOwner get() = navigation.owner

    /** What the pages get back, so their own handlers still hear the system. */
    val pageOwner: NavigationEventDispatcherOwner get() = realOwner

    init {
        controller.onFinished = { committed ->
            AppLog.info(
                category = "navigation.back",
                event = "zoom_finished",
                message = "Back gesture animation finished",
                attributes = mapOf("committed" to committed.toString()),
            )
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
    }

    fun detach() {
        abandonGesture()
        navigation.dispose()
    }

    fun frame(key: String): ZoomFrame = frames.getOrPut(key) { ZoomFrame() }

    fun forget(key: String) {
        frames.remove(key)
        if (retiredPages.remove(key) != null) {
            AppLog.info("navigation.back", "route_disposed", "Hidden outgoing route left composition")
        }
    }

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
        visible: Boolean = true,
    ) {
        this.visible = visible
        standIn.isBackEnabled = visible && keys.size > 1
        // A gesture belongs to the page it previewed, even when an external action changes
        // the route before the platform sends its terminal event.
        if (!visible || (systemStack != null && systemStack != keys)) abandonGesture()
        if (pushedFrom != null && keys.size >= 2) {
            val underlay = keys[keys.size - 2]
            origins[keys.last()] = ZoomOrigin(pushedFrom, underlay, frames[underlay]?.size ?: Size.Zero)
        }
        origins.keys.filter { it !in keys }.forEach { origins.remove(it) }
        // The same detail can be opened again before NavDisplay disposes its old entry.
        retiredPages.keys.filter { it in keys }.forEach { retiredPages.remove(it) }
        val page = pageKey
        if (page != null && (page !in keys || keys.last() != page)) {
            if (controller.phase == ZoomBackPhase.Done && page !in keys && frames.containsKey(page)) {
                retiredPages[page] = Unit
            } else {
                navigation.cancelled()
            }
            controller.reset()
            clearGesture()
        }
        if (keys.size < stack.size) {
            AppLog.info(
                category = "navigation.back",
                event = "stack_popped",
                message = "Navigation stack returned to an earlier page",
                attributes =
                    mapOf(
                        "previousDepth" to stack.size.toString(),
                        "depth" to keys.size.toString(),
                        "retainedHiddenPages" to retiredPages.size.toString(),
                    ),
            )
        }
        stack = keys
    }

    private fun systemStarted(event: NavigationEvent) {
        val top = stack.lastOrNull()
        systemStack = stack.toList()
        // A side return also cancels an unfinished poster entry when NavDisplay must
        // handle the preview itself (for example with a screen reader enabled).
        if (visible && stack.size > 1 && controller.canStartSide) onSideStarted()
        system =
            when {
                !visible || !controller.canStartSide -> SystemRoute.Ignored
                top != null && stack.size > 1 && !blocked() && beginGesture(top, useOrigin = false) -> {
                    if (controller.startSide(BACK_NAVIGATION_TOWARD, event.progress, detailPreview)) {
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
            SystemRoute.Zoom -> controller.moveSide(event.progress)
            SystemRoute.Forward -> navigation.progressed(event)
            SystemRoute.None, SystemRoute.Ignored -> Unit
        }
    }

    private fun systemCompleted() {
        val route = system
        system = SystemRoute.None
        systemStack = null
        when (route) {
            SystemRoute.Zoom -> controller.commitSide()
            SystemRoute.Forward -> navigation.completed()
            SystemRoute.Ignored -> Unit
            // A back with no preview: what NavDisplay's own handler would have done.
            SystemRoute.None -> if (visible && controller.idle) onBack()
        }
    }

    private fun systemCancelled() {
        val route = system
        system = SystemRoute.None
        systemStack = null
        when (route) {
            SystemRoute.Zoom -> controller.cancelSide()
            SystemRoute.Forward -> navigation.cancelled()
            SystemRoute.None, SystemRoute.Ignored -> Unit
        }
    }

    private fun abandonGesture() {
        // Disabling a NavigationEventHandler does not detach it from a gesture already
        // in progress. Keep ignoring that gesture's completion rather than treating it
        // as an ordinary back on the replacement page.
        if (system != SystemRoute.None) system = SystemRoute.Ignored
        navigation.cancelled()
        controller.reset()
        clearGesture()
    }

    private fun beginGesture(
        key: String,
        useOrigin: Boolean = true,
    ): Boolean {
        val underlay = stack.getOrNull(stack.size - 2) ?: return false
        val size = frames[key]?.size ?: return false
        if (size.width <= 0f || size.height <= 0f) return false
        controller.page = size
        pageKey = key
        underlayKey = underlay
        sources.wanted = if (useOrigin) origins[key]?.key else null
        AppLog.info("navigation.back", "zoom_started", "Back gesture started")
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
 * Keeps NavDisplay's scene lifecycle separate from the Activity callback that receives system back.
 * Nested stacks register after their parents, so the visible deepest page keeps back priority.
 */
@Composable
internal fun rememberZoomBackNavHost(canGoBack: Boolean): ZoomBackNavHost? {
    val realOwner = LocalNavigationEventDispatcherOwner.current
    if (realOwner == null) return null
    val scope = rememberCoroutineScope()
    val origins = rememberSaveable(saver = ZoomOriginsSaver) { mutableStateMapOf<String, ZoomOrigin>() }
    val host = remember(realOwner) { ZoomBackNavHost(ZoomBackController(scope), realOwner, origins) }
    DisposableEffect(host) {
        host.attach()
        onDispose { host.detach() }
    }
    ConfigureZoomBack(host.controller)
    PlatformNavigationBackHandler(
        enabled = canGoBack,
        onStarted = host.standIn::onBackStarted,
        onProgressed = host.standIn::onBackProgressed,
        onCompleted = host.standIn::onBackCompleted,
        onCancelled = host.standIn::onBackCancelled,
    )
    return host
}

/** Hands a controller what it reads from the composition: motion, haptics, tips, sizes, its overlay layer. */
@Composable
private fun ConfigureZoomBack(controller: ZoomBackController) {
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val tips = LocalTips.current
    val still = LocalAccessibilityOptions.current.reduceMotion
    val artLayer = rememberGraphicsLayer()
    SideEffect {
        controller.still = still
        controller.haptics = haptics
        controller.tips = tips
        controller.artLayer = artLayer
        with(density) {
            controller.detailCornerPx = DetailBackCorner.toPx()
            controller.cornerAtFullPx = ZoomBackCorner.toPx()
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
 * Layers for system back previews. Content scrolling has no dismissal handler.
 */
@Composable
internal fun Modifier.zoomBackRoute(
    host: ZoomBackNavHost?,
    key: String,
): Modifier {
    if (host == null) return this
    DisposableEffect(host, key) {
        onDispose { host.forget(key) }
    }
    return this
        .zoomBackLayers(host.controller, role = { host.roleOf(key) }, onInner = { host.frame(key).inner = it })
        .graphicsLayer { alpha = if (host.isRetired(key)) 0f else 1f }
}

/**
 * The same Activity-backed gesture bridge used by navigation stacks, also for fullscreen overlays.
 */
@Composable
internal fun ZoomBackSystemBack(
    enabled: Boolean,
    onStarted: (NavigationEvent) -> Unit,
    onProgressed: (NavigationEvent) -> Unit,
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
) {
    PlatformNavigationBackHandler(
        enabled = enabled,
        onStarted = onStarted,
        onProgressed = onProgressed,
        onCompleted = onCompleted,
        onCancelled = onCancelled,
    )
}

/**
 * A full-screen page drawn over another — 全部剧集, 查看全部 — whose system back preview slides the
 * opaque page off screen. Scrolling its content never closes it.
 * [onZoomedAway] hears that the page has already gone, before [onBack] closes it.
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
    val pageBackground = LocalPalette.current.background
    val scope = rememberCoroutineScope()
    val controller = remember { ZoomBackController(scope) }
    val frame = remember { ZoomFrame() }
    val latestBack by rememberUpdatedState(onBack)
    val latestAway by rememberUpdatedState(onZoomedAway)
    val latestSource by rememberUpdatedState(source)
    val latestVisible by rememberUpdatedState(visible)
    val gestureSource = remember { arrayOfNulls<ZoomBackAnchor>(1) }
    val wasVisible = remember { booleanArrayOf(visible) }
    ConfigureZoomBack(controller)
    DisposableEffect(controller) {
        onDispose {
            controller.reset()
            gestureSource[0]?.wanted = false
            gestureSource[0] = null
        }
    }
    SideEffect {
        if ((!visible && controller.phase != ZoomBackPhase.Done) || (visible && !wasVisible[0])) {
            controller.reset()
            gestureSource[0]?.wanted = false
            gestureSource[0] = null
        }
        wasVisible[0] = visible
        controller.onStarted = {
            gestureSource[0] = latestSource.takeUnless { controller.sideGesture }
            gestureSource[0]?.wanted = true
        }
        controller.onFinished = { committed ->
            gestureSource[0]?.wanted = false
            gestureSource[0] = null
            if (committed && latestVisible) {
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
                controller.canStartSide &&
                controller.startSide(BACK_NAVIGATION_TOWARD, event.progress)
        },
        onProgressed = { event -> if (sideRouted[0]) controller.moveSide(event.progress) },
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
            // On its way out — closed, or let go past the point of no return — the page takes no
            // touch: a row still fading is not there to be tapped. Checked on every event, so a
            // press that began before it left cannot end as a click on it either. Closed, it is not
            // read out any more.
            .pointerInput(controller) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!latestVisible || controller.leaving) event.changes.forEach { it.consume() }
                    }
                }
            }.then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .onPlaced {
                frame.outer = it
                controller.page = it.size.toSize()
            }.fillMaxSize()
            .zoomBackLayers(
                controller,
                role = { if (controller.active) ZoomBackRole.Page else ZoomBackRole.None },
            ).drawBehind { drawRect(pageBackground) },
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
