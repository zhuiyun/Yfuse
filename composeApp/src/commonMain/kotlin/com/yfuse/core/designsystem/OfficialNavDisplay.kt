package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
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
     * Routes that only hand off to somewhere else — the player route, which starts the player
     * Activity and pops itself. They are composed so their effects run, but never shown: pushing
     * one used to fade the page out and straight back in under the player's own transition.
     */
    isLauncher: (T) -> Boolean = { false },
    content: @Composable (T) -> Unit,
) {
    val parentRouteVisible = LocalRouteVisible.current
    val parentVisibility = LocalRouteVisibilityState.current
    val currentContent by rememberUpdatedState(content)
    val launchers = backStack.filter(isLauncher)
    val shownStack = backStack.filterNot(isLauncher).ifEmpty { backStack }
    val currentTop by rememberUpdatedState(shownStack.last())
    val sharedMediaController = remember { SharedMediaTransitionController() }
    val previousDepth = remember { intArrayOf(shownStack.size) }
    if (shownStack.size < previousDepth[0]) {
        // The route follows predictive back, but the forward-only artwork morph must not run
        // in reverse over it. Suppress that overlay before the smaller stack is composed.
        sharedMediaController.suppressForPop()
    }
    SideEffect { previousDepth[0] = shownStack.size }
    val activeSharedKey = sharedMediaController.activeKey
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val calm = calmMotion()
    val density = LocalDensity.current
    val searchTravelPx = with(density) { Motion.searchTravel.roundToPx() }
    val pushTravelPx = with(density) { Motion.pushOffset.roundToPx() }
    val popTravelPx = with(density) { Motion.popOffset.roundToPx() }
    val calmTravelPx = with(density) { CalmTravel.roundToPx() }
    // Only a stacked route rounds its corners on the way out. The root tabs and 搜索 never
    // did — the amount was pinned at 0 — yet every entry still paid for the transition
    // animation and a clipping layer.
    val roundsCorners = motion == OfficialNavMotion.Stack && !reduceMotion && !calm
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
                            derivedStateOf { (parentVisibility?.value ?: true) && entryKey == currentTop }
                        }
                    CompositionLocalProvider(
                        LocalRouteVisible provides
                            (parentRouteVisible && entryKey == currentTop),
                        LocalRouteVisibilityState provides visibility,
                    ) {
                        // The animation and its layer are conditional; the Box is not. Switching
                        // 减弱动态效果 from a pushed settings page flips [roundsCorners] under that
                        // very page, and a Box per branch would dispose and rebuild it.
                        val corners =
                            if (roundsCorners) {
                                val visibility = LocalNavAnimatedContentScope.current
                                val edge =
                                    visibility.transition.animateFloat(
                                        transitionSpec = { tween(Motion.POP, easing = Motion.Curve) },
                                        label = "routeReturnCorners",
                                    ) { if (it == EnterExitState.PostExit) 1f else 0f }
                                Modifier.graphicsLayer {
                                    val amount = edge.value
                                    shape = RoundedCornerShape(Motion.routeReturnCorner * amount)
                                    clip = amount > 0f
                                }
                            } else {
                                Modifier
                            }
                        Box(Modifier.fillMaxSize().then(corners)) { currentContent(entryKey) }
                    }
                }
            }
            NavDisplay(
                backStack = shownStack,
                modifier = Modifier.fillMaxSize(),
                onBack = onBack,
                transitionSpec = {
                    if (calm && !reduceMotion) {
                        calmContentTransform(motion, calmTravelPx, popping = false)
                    } else {
                        rootContentTransform(
                            motion,
                            reduceMotion,
                            searchTravelPx,
                            pushTravelPx,
                            popTravelPx,
                            popping = false,
                        )
                    }
                },
                popTransitionSpec = {
                    if (calm && !reduceMotion) {
                        calmContentTransform(motion, calmTravelPx, popping = true)
                    } else {
                        rootContentTransform(
                            motion,
                            reduceMotion,
                            searchTravelPx,
                            pushTravelPx,
                            popTravelPx,
                            popping = true,
                        )
                    }
                },
                predictivePopTransitionSpec = {
                    if (calm && !reduceMotion) {
                        calmContentTransform(motion, calmTravelPx, popping = true, predictive = true)
                    } else {
                        rootContentTransform(
                            motion,
                            reduceMotion,
                            searchTravelPx,
                            pushTravelPx,
                            popTravelPx,
                            popping = true,
                            predictive = true,
                        )
                    }
                },
                entryProvider = entryProvider,
            )
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
 * new page in over [CALM_PUSH_MS] as it slides [CalmTravel]; going back, and every switch between
 * tabs or into 搜索, is a [CALM_SWAP_MS] crossfade.
 */
private fun calmContentTransform(
    motion: OfficialNavMotion,
    travelPx: Int,
    popping: Boolean,
    predictive: Boolean = false,
): ContentTransform {
    val easing = if (predictive) LinearEasing else Motion.Curve
    val swap = tween<Float>(CALM_SWAP_MS, easing = easing)
    val transform =
        when {
            motion != OfficialNavMotion.Stack -> fadeIn(swap) togetherWith fadeOut(swap)
            popping ->
                fadeIn(swap) togetherWith
                    (fadeOut(swap) + slideOutHorizontally(tween(CALM_SWAP_MS, easing = easing)) { travelPx })
            else ->
                (
                    fadeIn(tween(CALM_PUSH_MS, easing = easing)) +
                        slideInHorizontally(tween(CALM_PUSH_MS, easing = easing)) { travelPx }
                ) togetherWith fadeOut(swap)
        }
    return ContentTransform(
        targetContentEnter = transform.targetContentEnter,
        initialContentExit = transform.initialContentExit,
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
    popTravelPx: Int,
    popping: Boolean,
    predictive: Boolean = false,
): ContentTransform {
    if (reduceMotion) return noBackTransition()
    if (predictive && motion == OfficialNavMotion.Stack) return predictiveStackTransform(popTravelPx)

    // A gesture's progress is the animation's progress. On the front-loaded house curve a 30%
    // swipe showed about 80% of the way back; linear keeps the page under the finger.
    val easing = if (predictive) LinearEasing else Motion.Curve
    // Going back from 搜索 is the search closing, whatever motion opened it: the root motion is
    // worked out from the tab being left, and at the search root that was still SearchEnter.
    val shown = if (popping && motion == OfficialNavMotion.SearchEnter) OfficialNavMotion.SearchExit else motion

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
        when (shown) {
            OfficialNavMotion.Stack -> stackContentTransform(popping, pushTravelPx, popTravelPx)
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
                        fadeOut(tween(Motion.QUICK, easing = easing)) +
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

/**
 * Predictive back on a stacked route: the page being pulled away is a card under the finger. It
 * stays on top, shrinks and slides with the gesture on a linear clock, and keeps its opacity
 * until the last stretch — which is nearly always after the release — while the page it returns
 * to fades in behind it. Fading the card with the swipe had it mostly gone by 30%.
 */
private fun predictiveStackTransform(popTravelPx: Int): ContentTransform =
    ContentTransform(
        targetContentEnter =
            fadeIn(tween(Motion.POP, easing = LinearEasing)) +
                slideInHorizontally(tween(Motion.POP, easing = LinearEasing)) { -popTravelPx },
        initialContentExit =
            scaleOut(tween(Motion.POP, easing = LinearEasing), targetScale = PREDICTIVE_EXIT_SCALE) +
                slideOutHorizontally(tween(Motion.POP, easing = LinearEasing)) { popTravelPx } +
                fadeOut(
                    tween(
                        durationMillis = PREDICTIVE_EXIT_FADE_MS,
                        delayMillis = Motion.POP - PREDICTIVE_EXIT_FADE_MS,
                        easing = LinearEasing,
                    ),
                ),
        targetContentZIndex = -1f,
        sizeTransform = null,
    )

private fun stackContentTransform(
    popping: Boolean,
    pushTravelPx: Int,
    popTravelPx: Int,
): ContentTransform =
    if (popping) {
        (
            fadeIn(tween(Motion.POP, easing = Motion.Curve)) +
                slideInHorizontally(tween(Motion.POP, easing = Motion.Curve)) {
                    -popTravelPx
                }
        ) togetherWith
            (
                fadeOut(tween(Motion.POP, easing = Motion.Curve)) +
                    slideOutHorizontally(tween(Motion.POP, easing = Motion.Curve)) {
                        popTravelPx
                    }
            )
    } else {
        (
            fadeIn(tween(Motion.PUSH, easing = Motion.Curve)) +
                slideInHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) {
                    pushTravelPx
                }
        ) togetherWith (
            // Long enough that the page being covered is still there while the new one gains
            // substance: at 120ms both were below half strength about 40ms in.
            fadeOut(tween(Motion.STANDARD, easing = Motion.Curve)) +
                slideOutHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { -pushTravelPx / 2 }
        )
    }

private const val ROOT_TAB_EXIT_SCALE = 0.994f
private const val SEARCH_SCALE_FROM = 0.97f

/** 静息's travel: a few dp, enough to say which way the page went. */
private val CalmTravel = 8.dp

/** 静息's push; everything else it does is a [CALM_SWAP_MS] crossfade. */
private const val CALM_PUSH_MS = 200
private const val CALM_SWAP_MS = 150

/** How far a page shrinks under a predictive back gesture — the platform's own 90%. */
private const val PREDICTIVE_EXIT_SCALE = 0.9f

/** The last stretch of a predictive back in which the pulled page finally fades. */
private const val PREDICTIVE_EXIT_FADE_MS = 90

/** How long a shared artwork morph may take to begin before its key is released regardless. */
private const val SHARED_MORPH_START_TIMEOUT_MS = 600L
