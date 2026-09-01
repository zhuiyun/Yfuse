package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay

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
    content: @Composable (T) -> Unit,
) {
    val parentRouteVisible = LocalRouteVisible.current
    val currentContent by rememberUpdatedState(content)
    val currentTop by rememberUpdatedState(backStack.last())
    val sharedMediaController = remember { SharedMediaTransitionController() }
    val previousDepth = remember { intArrayOf(backStack.size) }
    if (backStack.size < previousDepth[0]) {
        // The route follows predictive back, but the forward-only artwork morph must not run
        // in reverse over it. Suppress that overlay before the smaller stack is composed.
        sharedMediaController.suppressForPop()
    }
    SideEffect { previousDepth[0] = backStack.size }
    val activeSharedKey = sharedMediaController.activeKey
    LaunchedEffect(activeSharedKey) {
        val key = activeSharedKey ?: return@LaunchedEffect
        delay((Motion.EXPAND + Motion.QUICK).toLong())
        sharedMediaController.finish(key)
    }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val density = LocalDensity.current
    val searchTravelPx = with(density) { 14.dp.roundToPx() }
    val pushTravelPx = with(density) { Motion.pushOffset.roundToPx() }
    val popTravelPx = with(density) { Motion.popOffset.roundToPx() }
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalSharedMediaTransitionController provides sharedMediaController,
        ) {
            val entryProvider: (T) -> NavEntry<T> = { key ->
                NavEntry(
                    key = key,
                    contentKey = contentKey(key),
                ) { entryKey ->
                    CompositionLocalProvider(
                        LocalRouteVisible provides
                            (parentRouteVisible && entryKey == currentTop),
                    ) {
                        currentContent(entryKey)
                    }
                }
            }
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = onBack,
                transitionSpec = {
                    rootContentTransform(
                        motion,
                        reduceMotion,
                        searchTravelPx,
                        pushTravelPx,
                        popTravelPx,
                        popping = false,
                    )
                },
                popTransitionSpec = {
                    rootContentTransform(
                        motion,
                        reduceMotion,
                        searchTravelPx,
                        pushTravelPx,
                        popTravelPx,
                        popping = true,
                    )
                },
                predictivePopTransitionSpec = {
                    rootContentTransform(
                        motion,
                        reduceMotion,
                        searchTravelPx,
                        pushTravelPx,
                        popTravelPx,
                        popping = true,
                    )
                },
                entryProvider = entryProvider,
            )
        }
    }
}

private fun noBackTransition(): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
    )

private fun rootContentTransform(
    motion: OfficialNavMotion,
    reduceMotion: Boolean,
    searchTravelPx: Int,
    pushTravelPx: Int,
    popTravelPx: Int,
    popping: Boolean,
): ContentTransform {
    if (reduceMotion) return noBackTransition()

    val tabEnter =
        fadeIn(tween(Motion.TAB, easing = Motion.Curve)) +
            scaleIn(
                animationSpec = Motion.settle(),
                initialScale = Motion.TAB_SCALE_FROM,
            )
    val tabExit =
        fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
            scaleOut(
                animationSpec = tween(Motion.QUICK, easing = Motion.Curve),
                targetScale = ROOT_TAB_EXIT_SCALE,
            )

    return when (motion) {
        OfficialNavMotion.Stack -> stackContentTransform(popping, pushTravelPx, popTravelPx)
        OfficialNavMotion.RootTab -> tabEnter togetherWith tabExit
        OfficialNavMotion.SearchEnter ->
            (
                fadeIn(tween(Motion.TAB, easing = Motion.Curve)) +
                    scaleIn(
                        animationSpec = Motion.settle(),
                        initialScale = SEARCH_SCALE_FROM,
                    ) +
                    slideInVertically(
                        animationSpec = tween(Motion.TAB, easing = Motion.Curve),
                        initialOffsetY = { searchTravelPx },
                    )
            ) togetherWith tabExit
        OfficialNavMotion.SearchExit ->
            tabEnter togetherWith
                (
                    fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
                        scaleOut(
                            animationSpec = tween(Motion.QUICK, easing = Motion.Curve),
                            targetScale = SEARCH_SCALE_FROM,
                        ) +
                        slideOutVertically(
                            animationSpec = tween(Motion.QUICK, easing = Motion.Curve),
                            targetOffsetY = { searchTravelPx },
                        )
                )
    }
}

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
        ) togetherWith fadeOut(tween(Motion.QUICK, easing = Motion.Curve))
    }

private const val ROOT_TAB_EXIT_SCALE = 0.994f
private const val SEARCH_SCALE_FROM = 0.97f
