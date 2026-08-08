package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }

private val LocalSharedAnimatedVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Keeps the outgoing and incoming Decompose children composed long enough for
 * shared artwork to travel between list, hero and detail layouts.
 *
 * [routeKey] must return a stable value per route (not per instance). It does two jobs:
 * it tells [AnimatedContent] when the content genuinely changed, and it scopes a
 * [rememberSaveableStateHolder] entry so each route keeps its `rememberSaveable` state
 * while it sits in the back stack. Without that holder every route is rebuilt from
 * scratch on the way back — `rememberLazyListState` is a `rememberSaveable`, so a list
 * scrolled halfway down snapped back to the top as soon as the user opened a detail
 * page and returned.
 *
 * Routes that can stack on themselves (detail → related detail) share one key and
 * therefore one saved scroll offset. Giving those a per-item key would keep a registry
 * entry alive for every item ever visited, which costs more than the wart is worth.
 */
@Immutable
private data class Route<T : Any>(val value: T, val depth: Int)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> SharedElementTransitionContainer(
    targetState: T,
    routeKey: (T) -> String,
    /**
     * How deep the navigation stack is right now — pushing grows it, popping shrinks it.
     *
     * It is the only thing that tells the two apart. Without it every route change used
     * one transition in one direction, so 返回 played the same 推进 animation as the push
     * that had opened the page, and §3.1's [Motion.POP] was referenced nowhere in the app.
     */
    depth: Int,
    content: @Composable (T) -> Unit,
) {
    val accessibility = LocalAccessibilityOptions.current
    val stateHolder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    // 推进 — 右侧 30px 滑入; 返回 — 左侧 22px 滑入.
    val pushOffset = with(density) { Motion.pushOffset.roundToPx() }
    val popOffset = with(density) { Motion.popOffset.roundToPx() }

    SharedTransitionLayout sharedTransition@{
        AnimatedContent(
            targetState = Route(targetState, depth),
            transitionSpec = {
                val popping = this.targetState.depth < initialState.depth
                val duration = when {
                    accessibility.reduceMotion -> 0
                    popping -> Motion.POP
                    else -> Motion.PUSH
                }
                val fade = tween<Float>(duration, easing = Motion.Curve)
                val slide = tween<IntOffset>(duration, easing = Motion.Curve)
                val entering = if (popping) -popOffset else pushOffset
                ((
                    fadeIn(fade) + slideInHorizontally(slide) { entering }
                    ) togetherWith (
                    // The outgoing page drifts the other way at half the distance, so the
                    // two are clearly one movement rather than two pages passing.
                    fadeOut(fade) + slideOutHorizontally(slide) { -entering / 2 }
                    )).apply {
                    // The destination owns all non-shared chrome. On a pop this keeps the
                    // outgoing detail buttons behind the library page instead of letting
                    // them float over it for the remainder of the exit animation.
                    targetContentZIndex = 1f
                }
            },
            contentKey = { routeKey(it.value) },
            label = "shared-media-route",
        ) animatedContent@{ child ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@sharedTransition,
                LocalSharedAnimatedVisibilityScope provides this@animatedContent,
            ) {
                stateHolder.SaveableStateProvider(routeKey(child.value)) {
                    content(child.value)
                }
            }
        }
    }
}

/** Links matching media artwork across a [SharedElementTransitionContainer]. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMediaElement(key: String?): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalSharedAnimatedVisibilityScope.current
    if (key == null || sharedTransitionScope == null || animatedVisibilityScope == null) return this

    return with(sharedTransitionScope) {
        this@sharedMediaElement.sharedElement(
            state = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            // Compose 1.7 can draw a just-detached shared element in the transition overlay
            // before it has current bounds, throwing "current bounds not set yet". Keeping media
            // in its normal layer still animates its bounds and makes that draw path unreachable.
            renderInOverlayDuringTransition = false,
        )
    }
}
