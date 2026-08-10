package com.yfuse.core.designsystem

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A 返回 gesture in flight, shared between the shell that receives it and the navigation
 * stack that has to draw it.
 *
 * The two have to be separate because only the stack knows what is *behind* the page being
 * dragged. Without that, the peek had nothing to reveal: the whole shell slid aside over the
 * app's ambient backdrop, so starting a back gesture showed a blank wash rather than the page
 * being returned to — the one thing the gesture exists to let the user look at.
 *
 * The gesture runs in two phases, and both are here because the committed one has to continue
 * from wherever the finger left off rather than restart:
 *
 *  - [progress] — the finger is down and driving it. Reversible; [onCancel] springs it home.
 *  - [finish] — it committed, and the page is thrown the rest of the way off-screen. Only
 *    when that lands does the stack actually pop, by which point both pages are already
 *    where the pop would have put them. The revealed route remains the drawing owner during
 *    [handoffInProgress] and is only moved back after the new target has settled.
 */
@Stable
class PredictiveBackState internal constructor(private val scope: CoroutineScope) {

    /**
     * Whether the page underneath has to be composed.
     *
     * A plain `progress > 0f` test would do the same job, but read from composition it would
     * rebuild the page below on every frame of the gesture. This flips twice per gesture.
     */
    var peeking by mutableStateOf(false)
        private set

    /**
     * The stack has popped, but the revealed route still owns drawing until its zero-duration
     * target transition is ready to receive the same movable subtree.
     */
    internal var handoffInProgress by mutableStateOf(false)
        private set

    /** 0f at the start of the gesture, 1f where it would commit. Read in the draw phase. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** 0f..1f as a committed gesture carries the page off-screen. Read in the draw phase. */
    var finish by mutableFloatStateOf(0f)
        private set

    /** Edge that drives the current gesture; retained through cancel/commit animation. */
    var swipeEdge by mutableStateOf(PredictiveBackSwipeEdge.Left)
        private set

    internal var reduceMotion: Boolean = false

    /**
     * Whether the stack currently has a page it can reveal — set by the stack itself.
     *
     * A gesture that cannot show what is underneath has nothing to peek at, so it is not
     * started at all and 返回 falls back to the ordinary transition. Half a gesture, with the
     * page sliding aside over an empty backdrop, is the bug this whole file exists to fix.
     */
    internal var canPeek: Boolean = false

    private var job: Job? = null

    /** True while the committed gesture is being thrown out, before the stack is told. */
    private var finishing = false

    /**
     * A pop that the gesture has already played out, waiting to be claimed by the stack.
     *
     * Deliberately not a snapshot state: the stack has to read it while it builds the
     * transition and clear it in the same breath, and a state read and written inside one
     * composition invalidates itself forever.
     */
    private var pendingCommit = false

    internal fun onProgress(value: Float, edge: PredictiveBackSwipeEdge) {
        // The throw is not interruptible: it is the tail of a decision already made, and a
        // second gesture landing mid-throw would drag back a page that is on its way out.
        if (finishing) return
        if (!peeking && !canPeek) return
        job?.cancel()
        job = null
        pendingCommit = false
        peeking = true
        swipeEdge = edge
        progress = value.coerceIn(0f, 1f)
    }

    internal fun onCancel() {
        if (finishing) return
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = 0f,
                animationSpec = Motion.settle(reduceMotion),
            ) { value, _ -> progress = value }
            peeking = false
            progress = 0f
            swipeEdge = PredictiveBackSwipeEdge.Left
        }
    }

    /**
     * The gesture committed — or an ordinary back press arrived, which is the [peeking] false
     * case: nothing has been animated, so [pop] runs straight away and the stack plays its
     * usual 返回 transition.
     */
    internal fun onCommit(pop: () -> Unit) {
        if (finishing) return
        if (!peeking) {
            pop()
            return
        }
        finishing = true
        job?.cancel()
        job = scope.launch {
            throwOut()
            // Keep the completed reveal painted while the stack changes ownership. Clearing
            // these draw values before pop removed the only root host one composition before
            // AnimatedContent placed its zero-duration target, exposing the window backdrop.
            pendingCommit = true
            handoffInProgress = true
            pop()
        }
    }

    /**
     * Commits a predictive gesture whose destination is already mounted independently.
     *
     * A tab-root back and a full-screen overlay do not transfer a movable route between two
     * navigation hosts: the page being revealed is already a sibling underneath. They therefore
     * must not enter [handoffInProgress], which only [SharedElementTransitionContainer] knows how
     * to finish. Complete the throw, switch/remove the top page, then clear the gesture in the same
     * coroutine instead.
     */
    internal fun onStandaloneCommit(commit: () -> Unit) {
        if (finishing) return
        if (!peeking) {
            commit()
            return
        }
        finishing = true
        job?.cancel()
        job = scope.launch {
            throwOut()
            commit()
            peeking = false
            progress = 0f
            finish = 0f
            handoffInProgress = false
            pendingCommit = false
            finishing = false
            swipeEdge = PredictiveBackSwipeEdge.Left
        }
    }

    private suspend fun throwOut() {
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = if (reduceMotion) 0 else THROW,
                easing = Motion.Curve,
            ),
        ) { value, _ -> finish = value }
    }

    /**
     * Whether the route change now being composed is the tail of a gesture — asked once per
     * change by the stack, and true only for the change the gesture itself caused.
     */
    internal fun consumePendingCommit(): Boolean {
        val pending = pendingCommit
        pendingCommit = false
        return pending
    }

    /**
     * Called by the route container after the post-pop target has remained settled for a frame.
     * Resetting all gesture state together moves the already-painted route back to its active
     * host in one recomposition.
     */
    internal fun completeCommitHandoff() {
        if (!handoffInProgress) return
        peeking = false
        progress = 0f
        finish = 0f
        handoffInProgress = false
        finishing = false
        swipeEdge = PredictiveBackSwipeEdge.Left
    }

    private companion object {
        /**
         * How long the committed gesture takes to carry the page out. Shorter than
         * [Motion.POP], because the user has already done most of the movement themselves.
         */
        const val THROW = 200
    }
}

@Composable
fun rememberPredictiveBackState(): PredictiveBackState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { PredictiveBackState(scope) }
    state.reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    return state
}

/**
 * The gesture the enclosing navigation stack should draw, or `null` where there is none —
 * which is also the state every non-Android target sits in permanently.
 */
val LocalPredictiveBack = staticCompositionLocalOf<PredictiveBackState?> { null }

/**
 * The page being dragged aside.
 *
 * Scale, inset and rounding are the shape Android 14's own predictive back draws, which is
 * also — not by accident — the shape iOS's interactive pop has always had: the page lifts off
 * the surface and starts to move aside, and the corners round as it stops being the whole
 * screen. Rounding matters as much as the movement; a full-bleed rectangle sliding sideways
 * reads as a bug.
 *
 * The backdrop is painted *inside* the layer rather than left to the shell's, because most
 * pages here are transparent over the ambient field. Without it the page being dragged and
 * the page underneath would both show through each other — a double exposure rather than two
 * pages in a stack.
 */
@Composable
fun Modifier.predictiveBackPeek(state: PredictiveBackState): Modifier {
    val backdrop = appBackdropBrushes()
    return this
        .graphicsLayer {
            val gesture = state.progress
            val thrown = state.finish
            if (gesture <= 0f && thrown <= 0f) return@graphicsLayer
            val eased = Motion.Curve.transform(gesture.coerceIn(0f, 1f))
            val exit = Motion.Curve.transform(thrown.coerceIn(0f, 1f))
            val lift = (1f - 0.08f * eased) * (1f - 0.04f * exit)
            val direction = if (state.swipeEdge == PredictiveBackSwipeEdge.Left) 1f else -1f
            scaleX = lift
            scaleY = lift
            translationX = direction * size.width * (0.06f * eased + 0.94f * exit)
            alpha = (1f - 0.2f * eased) * (1f - exit)
            shape = GlassShapes.sheet
            clip = true
        }
        .drawBehind {
            if (state.progress <= 0f && state.finish <= 0f) return@drawBehind
            backdrop.forEach { drawRect(it) }
        }
}

/**
 * The page being revealed, held one step behind the finger.
 *
 * It settles to exactly where the stack will place it, so the pop that follows a committed
 * gesture has nothing left to move — see [PredictiveBackState.consumePendingCommit].
 */
@Composable
fun PredictiveBackReveal(state: PredictiveBackState, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val eased = Motion.Curve.transform(state.progress.coerceIn(0f, 1f))
                val exit = Motion.Curve.transform(state.finish.coerceIn(0f, 1f))
                val settled = eased + (1f - eased) * exit
                val direction = if (state.swipeEdge == PredictiveBackSwipeEdge.Left) 1f else -1f
                translationX = -direction * size.width * 0.10f * (1f - settled)
                alpha = 0.82f + 0.18f * settled
            }
            // It is a preview, not a page yet. The layer above it is scaled down, so the
            // strip of this one showing along the left edge is genuinely hittable, and a
            // tap landing there would act on a screen the user is still deciding about.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        content()
    }
}
