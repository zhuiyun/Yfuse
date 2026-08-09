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
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }

private val LocalSharedAnimatedVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Whether this movable route is the navigation target right now.
 *
 * Retained routes still compose so painters and backdrop layers survive, but work that only
 * makes sense on screen (focus, system bars and carousel clocks) pauses under the detail page.
 */
val LocalRouteVisible = staticCompositionLocalOf { true }

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

private typealias MovableRouteContent<T> = @Composable (T) -> Unit

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
    /**
     * What 返回 would land on, or `null` at the root of the stack.
     *
     * Retained after a push and moved between the hidden underlay, predictive preview and
     * returning transition. That keeps the exact same Compose tree alive until it is visible
     * again instead of rebuilding its painters, backdrop and ordinary remembered state.
     */
    previous: T? = null,
    content: @Composable (T) -> Unit,
) {
    val accessibility = LocalAccessibilityOptions.current
    val stateHolder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    // 推进 keeps the restrained 30px offset. 返回 moves the opaque detail page fully
    // aside and reveals the retained route underneath, matching predictive back.
    val pushOffset = with(density) { Motion.pushOffset.roundToPx() }
    val back = LocalPredictiveBack.current
    // Claimed once per route change, while it is happening: a pop the user has already
    // dragged all the way out has nothing left to animate. Asking during composition — not
    // from an effect afterwards — is what makes the answer available to the transition being
    // built right here.
    val settledPop = remember(targetState) { back?.consumePendingCommit() == true }

    // Keep each route's Compose-only state with the route while it moves between the active
    // transition and the retained underlay. Decompose already keeps the component and store,
    // but without movable content Coil painters, backdrop layers and ordinary remember values
    // were disposed after push and rebuilt after pop.
    val latestContent by rememberUpdatedState(content)
    val movableRoutes = remember {
        mutableMapOf<String, MovableRouteContent<T>>()
    }
    fun movableRoute(key: String): MovableRouteContent<T> =
        movableRoutes.getOrPut(key) {
            movableContentOf { value: T ->
                stateHolder.SaveableStateProvider(key) {
                    latestContent(value)
                }
            }
        }

    val transition = updateTransition(
        targetState = Route(targetState, depth),
        label = "shared-media-route",
    )
    val transitionSettled = transition.currentState == transition.targetState
    val previousValue = previous
    val targetRouteKey = routeKey(targetState)
    val previousRouteKey = previousValue?.let(routeKey)

    // A gesture can only reveal a route once AnimatedContent has handed that exact movable
    // subtree to the retained underlay.
    back?.canPeek =
        transitionSettled &&
            previousValue != null &&
            previousRouteKey != null &&
            previousRouteKey != targetRouteKey

    Box(Modifier.fillMaxSize()) {
        val peek = if (back != null) Modifier.predictiveBackPeek(back) else Modifier
        SharedTransitionLayout(modifier = Modifier.fillMaxSize().then(peek)) sharedTransition@{
        when {
            back?.peeking == true &&
                transitionSettled &&
                previousValue != null &&
                previousRouteKey != null -> {
                PredictiveBackReveal(back) {
                    CompositionLocalProvider(LocalRouteVisible provides false) {
                        if (previousRouteKey == targetRouteKey) {
                            // Related detail pages deliberately share a route key and cannot invoke
                            // the same movable SaveableStateProvider twice at the same time.
                            latestContent(previousValue)
                        } else {
                            movableRoute(previousRouteKey)(previousValue)
                        }
                    }
                }
            }

            transitionSettled &&
                previousValue != null &&
                previousRouteKey != null &&
                previousRouteKey != targetRouteKey -> {
                // At push completion AnimatedContent releases the outgoing route in the same
                // recomposition in which this host receives that exact movable subtree. It stays
                // composed and measured without visible output, ready for pop to reveal.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0f }
                        .clearAndSetSemantics { },
                ) {
                    CompositionLocalProvider(LocalRouteVisible provides false) {
                        movableRoute(previousRouteKey)(previousValue)
                    }
                }
            }
        }

            transition.AnimatedContent(
                transitionSpec = {
                    val popping = this.targetState.depth < initialState.depth
                    // A committed gesture has already played this transition under the
                    // finger: both pages are where it would have put them, so animating
                    // one now would move them a second time.
                    val duration = when {
                        (popping && settledPop) || accessibility.reduceMotion -> 0
                        popping -> Motion.POP
                        else -> Motion.PUSH
                    }
                    val fade = tween<Float>(duration, easing = Motion.Curve)
                    val slide = tween<IntOffset>(duration, easing = Motion.Curve)
                    (
                        if (popping) {
                            // The fully opaque detail page exits to the right while the retained
                            // route settles from a small left parallax underneath. At the final
                            // frame the detail is already outside the viewport, so disposing it
                            // cannot produce an abrupt full-screen cut.
                            slideInHorizontally(slide) { -it / 10 } togetherWith
                                slideOutHorizontally(slide) { it }
                        } else {
                            (fadeIn(fade) + slideInHorizontally(slide) { pushOffset }) togetherWith
                                (fadeOut(fade) + slideOutHorizontally(slide) { -pushOffset / 2 })
                        }
                    ).apply {
                        targetContentZIndex = if (popping) -1f else 1f
                    }
                },
                contentKey = { routeKey(it.value) },
            ) animatedContent@{ child ->
                val childKey = routeKey(child.value)
                // AnimatedContent can report itself settled for one composition while it still
                // visits the outgoing child. The retained host owns that movable subtree now,
                // so skip the stale invocation and keep every provider mounted exactly once.
                if (!transitionSettled || childKey == targetRouteKey) {
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides this@sharedTransition,
                        LocalSharedAnimatedVisibilityScope provides this@animatedContent,
                        LocalRouteVisible provides (childKey == targetRouteKey),
                    ) {
                        movableRoute(childKey)(child.value)
                    }
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
