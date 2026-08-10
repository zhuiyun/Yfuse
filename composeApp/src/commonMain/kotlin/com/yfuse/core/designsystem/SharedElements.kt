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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** Retained routes stay composed, but off-screen work such as focus and carousel clocks pauses. */
val LocalRouteVisible = staticCompositionLocalOf { true }

@Immutable
private data class Route<T : Any>(val value: T, val depth: Int)

/** Stable detail identity shared by the Home, Search and Library navigation hosts. */
internal fun detailRouteIdentity(serverId: String?, itemId: String): String {
    val server = serverId.orEmpty()
    return "detail:${server.length}:$server:${itemId.length}:$itemId"
}

/** Tracks popped route state so saveable registries do not grow for the life of the process. */
internal class RouteRetentionTracker(initialTargetKey: String) {
    private var observedTargetKey = initialTargetKey
    private val pendingRemovals = linkedSetOf<String>()

    fun observe(targetKey: String, previousRouteKey: String?) {
        if (targetKey == observedTargetKey) return
        val departedKey = observedTargetKey
        if (departedKey != previousRouteKey) pendingRemovals += departedKey
        pendingRemovals -= targetKey
        observedTargetKey = targetKey
    }

    fun removalsWhenSettled(targetKey: String, previousRouteKey: String?): Set<String> {
        if (targetKey != observedTargetKey) return emptySet()
        val removable = pendingRemovals.filterTo(linkedSetOf()) { key ->
            key != targetKey && key != previousRouteKey
        }
        pendingRemovals.removeAll(removable)
        return removable
    }
}

/**
 * Navigation host with official predictive-back progress wired to the minimum useful visual:
 * the current route translates with the user's finger and the actual previous route is shown
 * underneath. There is no predictive scale, fade, corner morph, scrim or post-gesture throw.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> SharedElementTransitionContainer(
    targetState: T,
    routeKey: (T) -> String,
    depth: Int,
    previous: T? = null,
    content: @Composable (T) -> Unit,
) {
    val accessibility = LocalAccessibilityOptions.current
    val stateHolder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    val pushOffset = with(density) { Motion.pushOffset.roundToPx() }
    val back = LocalBackGesture.current
    val latestContent by rememberUpdatedState(content)

    val targetKey = routeKey(targetState)
    val previousValue = previous
    val previousKey = previousValue?.let(routeKey)
    val transition = updateTransition(
        targetState = Route(targetState, depth),
        label = "shared-media-route",
    )
    val transitionSettled = transition.currentState == transition.targetState
    val gestureCommitted = remember(targetState) { back?.consumeCommittedGesture() == true }

    val retention = remember { RouteRetentionTracker(targetKey) }
    retention.observe(targetKey, previousKey)
    LaunchedEffect(transitionSettled, targetKey, previousKey) {
        if (transitionSettled) {
            retention.removalsWhenSettled(targetKey, previousKey)
                .forEach(stateHolder::removeState)
        }
    }

    val canRevealPrevious =
        transitionSettled && previousValue != null && previousKey != null && previousKey != targetKey
    val gestureVisible =
        canRevealPrevious && back?.active == true && !accessibility.reduceMotion

    Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) sharedTransition@{
            // Keep the exact previous route mounted after a push so a back gesture reveals
            // real content immediately. It is inert and invisible until the system gesture starts.
            if (
                transitionSettled &&
                previousValue != null &&
                previousKey != null &&
                previousKey != targetKey
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (gestureVisible) 1f else 0f }
                        .clearAndSetSemantics { },
                ) {
                    CompositionLocalProvider(LocalRouteVisible provides false) {
                        stateHolder.SaveableStateProvider(previousKey) {
                            latestContent(previousValue)
                        }
                    }
                }
            }

            val gestureModifier = if (gestureVisible && back != null) {
                Modifier.graphicsLayer {
                    val direction = if (back.edge == BackGestureEdge.Right) -1f else 1f
                    translationX = direction * size.width * back.progress.coerceIn(0f, 1f)
                }
            } else {
                Modifier
            }

            transition.AnimatedContent(
                modifier = Modifier.fillMaxSize().then(gestureModifier),
                transitionSpec = {
                    val popping = this.targetState.depth < initialState.depth
                    val duration = when {
                        accessibility.reduceMotion -> 0
                        popping && gestureCommitted -> 0
                        popping -> Motion.POP
                        else -> Motion.PUSH
                    }
                    val fade = tween<Float>(duration, easing = Motion.Curve)
                    val slide = tween<IntOffset>(duration, easing = Motion.Curve)
                    (
                        if (popping) {
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
                // Once settled, the hidden underlay owns the previous route. AnimatedContent
                // renders only the target so the same SaveableStateProvider is never mounted twice.
                if (!transitionSettled || childKey == targetKey) {
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides this@sharedTransition,
                        LocalSharedAnimatedVisibilityScope provides this@animatedContent,
                        LocalRouteVisible provides (childKey == targetKey),
                    ) {
                        stateHolder.SaveableStateProvider(childKey) {
                            latestContent(child.value)
                        }
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
            renderInOverlayDuringTransition = false,
        )
    }
}
