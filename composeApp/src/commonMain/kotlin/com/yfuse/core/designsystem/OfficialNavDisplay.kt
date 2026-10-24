package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Motion reserved for the root destinations; nested stacks keep their existing behavior. */
enum class OfficialNavMotion {
    Stack,
    RootTab,
    SearchEnter,
    SearchExit,
}

/** AndroidX Navigation 3 host with gesture-seekable stack motion and opt-in root motion. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> OfficialNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    contentKey: (T) -> String,
    modifier: Modifier = Modifier,
    motion: OfficialNavMotion = OfficialNavMotion.Stack,
    /**
     * How going back looks, a back gesture's preview included. That preview starts before
     * anything has changed, while [motion] still names how the page on top arrived — and at the
     * root the two differ: back from a tab reached from 搜索 is a tab switch, not 搜索 closing.
     */
    popMotion: OfficialNavMotion = motion,
    /**
     * Routes that only hand off to somewhere else — the player route, which starts the player
     * Activity and pops itself. They are composed so their effects run, but never shown: pushing
     * one used to fade the page out and straight back in under the player's own transition.
     */
    isLauncher: (T) -> Boolean = { false },
    /** Detail entry keeps its shared poster; return uses one opaque, predictive side slide. */
    isDetail: (T) -> Boolean = { false },
    content: @Composable (T) -> Unit,
) {
    val parentRouteVisible = LocalRouteVisible.current
    val parentVisibility = LocalRouteVisibilityState.current ?: rememberUpdatedState(parentRouteVisible)
    val currentContent by rememberUpdatedState(content)
    val launchers = backStack.filter(isLauncher)
    val shownStack = backStack.filterNot(isLauncher).ifEmpty { backStack }
    val currentTop by rememberUpdatedState(shownStack.last())
    val latestBack by rememberUpdatedState(onBack)
    val sharedMediaController = remember { SharedMediaTransitionController() }
    // Register each stack with Activity before its pages, preserving the deepest visible
    // handler's priority while NavDisplay receives the matching private scene events.
    val zoom = rememberZoomBackNavHost(canGoBack = parentRouteVisible && shownStack.size > 1)
    val liftMenu = LocalLiftMenu.current
    val screenReader = rememberScreenReaderActive()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val calm = calmMotion()
    val cinema = cinemaMotion()
    val routeOrigins = remember { MediaRouteOrigins() }
    val routeKeys = shownStack.map(contentKey)
    val detailRoutes = remember { DetailReturnRoutes() }
    val detailReturn = detailRoutes.isDetailReturn(routeKeys)
    val topIsDetail = isDetail(shownStack.last())
    val returnPoster = routeOrigins.returningTo(routeKeys)
    val cinemaPoster = cinema && (sharedMediaController.activeKey != null || returnPoster != null)
    val previousDepth = remember { intArrayOf(shownStack.size) }
    SideEffect {
        // A route pushed right after a poster was tapped is a route from that poster.
        val pushedFrom = if (shownStack.size > previousDepth[0]) sharedMediaController.takeOrigin() else null
        if (shownStack.size < previousDepth[0] && (cinema || detailReturn)) {
            if (detailReturn || zoom?.suppressPopTransition == true || returnPoster == null) {
                sharedMediaController.suppressForPop()
            } else {
                sharedMediaController.onPop(returnPoster)
            }
        }
        routeOrigins.update(routeKeys, pushedFrom)
        sharedMediaController.prepareReturn(
            if (cinema && !topIsDetail) routeOrigins.returningTo(routeKeys.dropLast(1)) else null,
        )
        detailRoutes.update(routeKeys, shownStack.filter(isDetail).map(contentKey).toSet())
        if (zoom != null) {
            zoom.onBack = { latestBack() }
            zoom.detailPreview = topIsDetail && !calm
            zoom.onSideStarted = {
                if (topIsDetail) sharedMediaController.suppressForPop()
            }
            zoom.blocked = {
                liftMenu?.isOpen == true ||
                    screenReader ||
                    (
                        !topIsDetail &&
                            (
                                sharedMediaController.activeKey != null ||
                                    (cinema && sharedMediaController.hasPreparedReturn)
                            )
                    )
            }
            zoom.onStack(routeKeys, pushedFrom, visible = parentRouteVisible)
        }
        previousDepth[0] = shownStack.size
    }
    val activeSharedKey = sharedMediaController.activeKey
    val density = LocalDensity.current
    // Retained entries must paint the current theme underneath translucent page content.
    val pageBackground by rememberUpdatedState(LocalPalette.current.background)
    val searchTravelPx = with(density) { Motion.searchTravel.roundToPx() }
    val pushTravelPx = with(density) { Motion.pushOffset.roundToPx() }
    val calmTravelPx = with(density) { CalmTravel.roundToPx() }
    SharedTransitionLayout(modifier.windowSizeHandoff()) {
        // The key is cleared once the morph has actually run, not after a fixed 420ms: a slow
        // first frame on a low-end phone used to pull the key out from under a morph in flight.
        LaunchedEffect(activeSharedKey) {
            val key = activeSharedKey ?: return@LaunchedEffect
            withTimeoutOrNull(SHARED_MORPH_START_TIMEOUT_MS) {
                snapshotFlow { isTransitionActive }.first { it }
            }
            snapshotFlow { isTransitionActive }.first { !it }
            sharedMediaController.finish(key)
        }
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalSharedMediaTransitionController provides sharedMediaController,
        ) {
            val entryProvider: (T) -> NavEntry<T> = { key ->
                NavEntry(
                    key = key,
                    contentKey = contentKey(key),
                ) { entryKey ->
                    val visibility =
                        remember(entryKey, parentVisibility) {
                            derivedStateOf { parentVisibility.value && entryKey == currentTop }
                        }
                    val routeKey = contentKey(entryKey)
                    CompositionLocalProvider(
                        *zoomBackRouteLocals(zoom, routeKey),
                        // NavDisplay retains entry content: read live visibility after the splash,
                        // rather than the parent's Boolean captured when this entry was created.
                        LocalRouteVisible provides visibility.value,
                        LocalRouteVisibilityState provides visibility,
                        LocalDetailSideReturn provides isDetail(entryKey),
                    ) {
                        val entryTransition = LocalNavAnimatedContentScope.current.transition
                        val returnCandidate = sharedMediaController.returnKey
                        val entrySettled =
                            entryTransition.currentState == EnterExitState.Visible &&
                                entryTransition.targetState == EnterExitState.Visible
                        SideEffect {
                            if (visibility.value && entrySettled) sharedMediaController.markReturnReady(returnCandidate)
                        }
                        Box(
                            Modifier
                                .zoomBackFrame(zoom, routeKey)
                                .fillMaxSize()
                                .zoomBackRoute(zoom, routeKey)
                                .detailBackCorners(isDetail(entryKey) && !calm && !reduceMotion) {
                                    zoom?.suppressPopTransition == true
                                }.drawBehind {
                                    // Glass content still needs a solid page beneath it while two
                                    // destinations share the host during a return gesture.
                                    if (motion == OfficialNavMotion.Stack ||
                                        entryTransition.currentState != entryTransition.targetState
                                    ) {
                                        drawRect(pageBackground)
                                    }
                                },
                        ) {
                            currentContent(entryKey)
                        }
                    }
                }
            }
            // Inside a zoom-back stack NavDisplay hears only the host: see ZoomBackNavigation.kt.
            CompositionLocalProvider(*zoomBackDisplayLocals(zoom)) {
                NavDisplay(
                    backStack = shownStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = onBack,
                    sceneStrategies =
                        remember(zoom) {
                            if (zoom == null) emptyList() else listOf(ZoomBackSceneStrategy<T>(zoom))
                        },
                    transitionSpec = {
                        if (cinemaPoster && motion == OfficialNavMotion.Stack) {
                            cinemaPosterPageTransform(popping = false)
                        } else if (calm && !reduceMotion) {
                            calmContentTransform(motion, calmTravelPx, popping = false)
                        } else {
                            rootContentTransform(
                                motion,
                                reduceMotion,
                                searchTravelPx,
                                pushTravelPx,
                                popping = false,
                            )
                        }
                    },
                    popTransitionSpec = {
                        if (zoom?.suppressPopTransition == true) {
                            zoomBackRouteTransform()
                        } else if (detailReturn) {
                            if (reduceMotion) noBackTransition() else detailBackTransform(embellished = !calm)
                        } else if (cinemaPoster && popMotion == OfficialNavMotion.Stack) {
                            cinemaPosterPageTransform(popping = true)
                        } else if (calm && !reduceMotion) {
                            calmContentTransform(popMotion, calmTravelPx, popping = true)
                        } else {
                            rootContentTransform(
                                popMotion,
                                reduceMotion,
                                searchTravelPx,
                                pushTravelPx,
                                popping = true,
                            )
                        }
                    },
                    predictivePopTransitionSpec = {
                        if (zoom?.suppressPopTransition == true) {
                            zoomBackRouteTransform()
                        } else if (topIsDetail) {
                            if (reduceMotion) {
                                noBackTransition()
                            } else {
                                detailBackTransform(
                                    predictive = true,
                                    embellished = !calm,
                                )
                            }
                        } else if (cinema &&
                            popMotion == OfficialNavMotion.Stack &&
                            (sharedMediaController.hasPreparedReturn || sharedMediaController.activeKey != null)
                        ) {
                            cinemaPosterPageTransform(popping = true)
                        } else if (reduceMotion) {
                            noBackTransition()
                        } else {
                            opaqueBackTransform(predictive = true)
                        }
                    },
                    entryProvider = entryProvider,
                )
            }
            launchers.forEach { launcher ->
                key(contentKey(launcher)) {
                    Box(Modifier.size(0.dp)) { currentContent(launcher) }
                }
            }
        }
    }
}

/**
 * 静息 — see [MotionTheme.Calm]: opacity and a few dp of travel, nothing scales. A push fades the
 * new page in over [CALM_PUSH_MS] as it slides [CalmTravel], and the page it covers out over the
 * same span. Back navigation uses the same opaque slide as the other motion themes.
 */
private fun calmContentTransform(
    motion: OfficialNavMotion,
    travelPx: Int,
    popping: Boolean,
    predictive: Boolean = false,
): ContentTransform {
    if (popping) return opaqueBackTransform(predictive = predictive)
    val easing = if (predictive) LinearEasing else Motion.Curve
    val swap = tween<Float>(CALM_SWAP_MS, easing = easing)
    val transform =
        when {
            motion != OfficialNavMotion.Stack -> fadeIn(swap) togetherWith fadeOut(swap)
            else ->
                (
                    fadeIn(tween(CALM_PUSH_MS, easing = easing)) +
                        slideInHorizontally(tween(CALM_PUSH_MS, easing = easing)) { travelPx }
                ) togetherWith fadeOut(tween(CALM_PUSH_MS, easing = easing))
        }
    return ContentTransform(
        targetContentEnter = transform.targetContentEnter,
        initialContentExit = transform.initialContentExit,
        targetContentZIndex = 0f,
        sizeTransform = null,
    )
}

private fun noBackTransition(): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        sizeTransform = null,
    )

private fun rootContentTransform(
    motion: OfficialNavMotion,
    reduceMotion: Boolean,
    searchTravelPx: Int,
    pushTravelPx: Int,
    popping: Boolean,
    predictive: Boolean = false,
): ContentTransform {
    if (reduceMotion) return noBackTransition()
    if (popping) return opaqueBackTransform(predictive = predictive)

    // A gesture's progress is the animation's progress. On the front-loaded house curve a 30%
    // swipe showed about 80% of the way back; linear keeps the page under the finger.
    val easing = if (predictive) LinearEasing else Motion.Curve

    val tabEnter =
        fadeIn(tween(Motion.TAB, easing = easing)) +
            scaleIn(
                animationSpec = tween(Motion.TAB, easing = easing),
                initialScale = Motion.TAB_SCALE_FROM,
            )
    // The outgoing tab fades over the same span the incoming one takes to arrive. Pages are
    // translucent over one backdrop, and a 120ms exit left both at under half strength for
    // the first frames — the backdrop flashed through between them.
    val tabExit =
        fadeOut(tween(Motion.TAB, easing = easing)) +
            scaleOut(
                animationSpec = tween(Motion.TAB, easing = easing),
                targetScale = ROOT_TAB_EXIT_SCALE,
            )

    val transform =
        when (motion) {
            OfficialNavMotion.Stack -> stackPushTransform(pushTravelPx)
            OfficialNavMotion.RootTab -> tabEnter togetherWith tabExit
            OfficialNavMotion.SearchEnter ->
                (
                    fadeIn(tween(Motion.TAB, easing = easing)) +
                        scaleIn(
                            animationSpec = tween(Motion.TAB, easing = easing),
                            initialScale = SEARCH_SCALE_FROM,
                        ) +
                        slideInVertically(
                            animationSpec = tween(Motion.TAB, easing = easing),
                            initialOffsetY = { searchTravelPx },
                        )
                ) togetherWith tabExit
            OfficialNavMotion.SearchExit ->
                tabEnter togetherWith
                    (
                        // It shrinks away over QUICK but fades over the returning tab's span, by the
                        // rule above: a QUICK fade left both pages under half strength for a moment.
                        fadeOut(tween(Motion.TAB, easing = easing)) +
                            scaleOut(
                                animationSpec = tween(Motion.QUICK, easing = easing),
                                targetScale = SEARCH_SCALE_FROM,
                            ) +
                            slideOutVertically(
                                animationSpec = tween(Motion.QUICK, easing = easing),
                                targetOffsetY = { searchTravelPx },
                            )
                    )
        }
    // Each route already fills the host. A second size animation only keeps both expensive pages
    // measured longer; opacity, translation and scale share the same finite hand-off instead.
    return ContentTransform(
        targetContentEnter = transform.targetContentEnter,
        initialContentExit = transform.initialContentExit,
        targetContentZIndex = transform.targetContentZIndex,
        sizeTransform = null,
    )
}

/** Full-size page translation; the destination stays fixed and both surfaces remain opaque. */
private fun opaqueBackTransform(predictive: Boolean = false): ContentTransform {
    val easing = if (predictive) LinearEasing else Motion.Curve
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit =
            slideOutHorizontally(tween(Motion.POP, easing = easing)) { (BACK_NAVIGATION_TOWARD * it).toInt() },
        targetContentZIndex = -1f,
        sizeTransform = null,
    )
}

private fun stackPushTransform(pushTravelPx: Int): ContentTransform =
    (
        fadeIn(tween(Motion.PUSH, easing = Motion.Curve)) +
            slideInHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) {
                pushTravelPx
            }
    ) togetherWith (
        // Over the same span as the new page's fade-in, on the same curve, so the two always add
        // up to one. Shorter, the page being covered went under half strength before the new
        // one had reached it — at 120ms about 40ms in, and still for 29–45ms at 180ms.
        fadeOut(tween(Motion.PUSH, easing = Motion.Curve)) +
            slideOutHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { -pushTravelPx / 2 }
    )

private const val ROOT_TAB_EXIT_SCALE = 0.994f
private const val SEARCH_SCALE_FROM = 0.97f

/** 静息's travel: a few dp, enough to say which way the page went. */
private val CalmTravel = 8.dp

/** 静息's push; everything else it does is a [CALM_SWAP_MS] crossfade. */
private const val CALM_PUSH_MS = 200
private const val CALM_SWAP_MS = 150

/** How long a shared artwork morph may take to begin before its key is released regardless. */
private const val SHARED_MORPH_START_TIMEOUT_MS = 600L
