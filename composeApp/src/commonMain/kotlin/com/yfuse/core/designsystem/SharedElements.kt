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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
 * [routeKey] must return a stable value per logical stack entry. It does two jobs:
 * it tells [AnimatedContent] when the content genuinely changed, and it scopes a
 * [rememberSaveableStateHolder] entry so each route keeps its `rememberSaveable` state
 * while it sits in the back stack. Without that holder every route is rebuilt from
 * scratch on the way back — `rememberLazyListState` is a `rememberSaveable`, so a list
 * scrolled halfway down snapped back to the top as soon as the user opened a detail
 * page and returned.
 *
 * Routes that can stack on themselves (detail → related detail) need distinct keys so the
 * previous detail can remain mounted for predictive-back preview. Entries are removed after
 * their pop transition settles, preventing already-popped details from accumulating here.
 */
@Immutable
private data class Route<T : Any>(val value: T, val depth: Int)

private typealias MovableRouteContent<T> = @Composable (T) -> Unit

/** Stable detail identity shared by the Home, Search and Library navigation hosts. */
internal fun detailRouteIdentity(serverId: String?, itemId: String): String {
    val server = serverId.orEmpty()
    // Length prefixes keep the pair unambiguous even if a compatible server uses punctuation
    // inside its ids.
    return "detail:${server.length}:$server:${itemId.length}:$itemId"
}

/**
 * Tracks route keys that left the stack even if their push never reached a settled frame.
 *
 * Target changes are observed during composition, while removal waits for the transition host
 * to settle. A normal push keeps the departed target because it is `previousRouteKey`; a pop
 * queues the departed target because it is no longer the page underneath.
 */
internal class RouteRetentionTracker(initialTargetKey: String) {
    private var observedTargetKey = initialTargetKey
    private val pendingRemovals = linkedSetOf<String>()

    fun observe(targetKey: String, previousRouteKey: String?) {
        if (targetKey == observedTargetKey) return
        val departedKey = observedTargetKey
        if (departedKey != previousRouteKey) pendingRemovals += departedKey
        // A route can become active again before the animation settles; it must no longer be
        // treated as obsolete in that case.
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
 * Retains the route revealed by a committed predictive-back gesture until ownership handoff
 * finishes.
 *
 * Popping a stack with at least three entries changes `previous` from the gesture destination
 * to the route below that destination in the same update that changes the target. Updating the
 * retained value while a gesture is finishing or handing off would therefore reveal one route
 * too far back and leave the handoff unable to complete. Outside a gesture, observing `previous`
 * synchronously keeps the next destination current without relying on effect ordering.
 */
internal class PredictiveBackRevealRouteTracker<T>(initialPrevious: T?) {
    private var retainedPrevious = initialPrevious

    fun reveal(previous: T?, frozen: Boolean): T? {
        if (!frozen && previous != null) {
            retainedPrevious = previous
        }
        return if (frozen) retainedPrevious else previous
    }
}

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
    val routeRetention = remember { RouteRetentionTracker(targetRouteKey) }
    // Do not defer this until the transition settles: B can be pushed and popped back to A
    // before any settled B frame exists, while its movable/saveable entries were still created.
    routeRetention.observe(targetRouteKey, previousRouteKey)

    // previous is derived from backStack.lastOrNull(), so after a pop it immediately becomes
    // the route below the gesture destination (or null at root). Freeze the destination while
    // ownership moves from PredictiveBackReveal to AnimatedContent; observing the new previous
    // during handoff would reveal one route too far back and make completion impossible.
    val revealRouteTracker = remember {
        PredictiveBackRevealRouteTracker(initialPrevious = previousValue)
    }
    val handoff = back?.handoffInProgress == true
    // peeking remains true through the throw and commit handoff, so it freezes earlier than
    // handoffInProgress and cannot lose the destination to a concurrent stack update.
    val revealValue = revealRouteTracker.reveal(previousValue, frozen = back?.peeking == true)
    val revealRouteKey = revealValue?.let(routeKey)
    var handoffTargetVisited by remember(targetRouteKey) { mutableStateOf(false) }

    // A gesture can only reveal a route once AnimatedContent has handed that exact movable
    // subtree to the retained underlay.
    back?.canPeek =
        transitionSettled &&
            previousValue != null &&
            previousRouteKey != null &&
            previousRouteKey != targetRouteKey

    // A committed preview owns the returned-to route until the zero-duration pop has settled.
    // Leave it painted for one full frame in that state, then move the same movable subtree to
    // AnimatedContent in the following recomposition.
    LaunchedEffect(
        back,
        handoff,
        handoffTargetVisited,
        transitionSettled,
        targetRouteKey,
        revealRouteKey,
    ) {
        if (
            handoff &&
            handoffTargetVisited &&
            transitionSettled &&
            revealRouteKey == targetRouteKey
        ) {
            withFrameNanos { }
            if (back?.handoffInProgress == true) {
                back.completeCommitHandoff()
            }
        }
    }

    // A push keeps the departed target as [previousRouteKey], because it is still in the
    // stack. A pop does not: once its transition/handoff settles, release both its movable
    // content wrapper and its saveable registry entry. Per-detail route keys can therefore
    // preserve related-detail peeks without retaining every title visited for the app lifetime.
    LaunchedEffect(
        handoff,
        transitionSettled,
        targetRouteKey,
        previousRouteKey,
    ) {
        if (!handoff && transitionSettled) {
            routeRetention.removalsWhenSettled(targetRouteKey, previousRouteKey)
                .forEach { departedKey ->
                    movableRoutes.remove(departedKey)
                    stateHolder.removeState(departedKey)
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Keep both routes in one lookahead scope, but move only the page being left.
        // Applying the gesture to SharedTransitionLayout also moved the retained reveal route,
        // exposing the Activity/window background as a white wash during predictive back.
        val peek = if (back != null) Modifier.predictiveBackPeek(back) else Modifier
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) sharedTransition@{
        when {
            back?.peeking == true &&
                (transitionSettled || handoff) &&
                revealValue != null &&
                revealRouteKey != null -> {
                PredictiveBackReveal(back) {
                    CompositionLocalProvider(LocalRouteVisible provides false) {
                        if (!handoff && revealRouteKey == targetRouteKey) {
                            // Equal-key replacements cannot invoke the same movable
                            // SaveableStateProvider twice at the same time.
                            latestContent(revealValue)
                        } else {
                            // During commit this remains the sole owner even after targetState has
                            // become the same route key. AnimatedContent is gated below until move.
                            movableRoute(revealRouteKey)(revealValue)
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
                modifier = Modifier.fillMaxSize().then(peek),
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
                val revealOwnsTarget =
                    handoff && revealRouteKey != null && childKey == revealRouteKey
                if (revealOwnsTarget && transitionSettled && childKey == targetRouteKey) {
                    // Only a settled composition that actually visits the target host may release
                    // the reveal. An earlier target visit can be followed by one outgoing-only
                    // composition, which is the exact gap this handoff prevents.
                    SideEffect { handoffTargetVisited = true }
                }
                if (!revealOwnsTarget && (!transitionSettled || childKey == targetRouteKey)) {
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
