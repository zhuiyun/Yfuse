package com.yfuse.core.designsystem

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun shouldDismissDialogDrag(
    distance: Float,
    velocity: Float,
    threshold: Float,
): Boolean =
    velocity >= -threshold * 4f &&
        (distance >= threshold || (distance >= threshold * 0.18f && velocity > threshold * 9f))

/**
 * Where the panel is drawn for [travel] of finger movement: one to one up to the commit point,
 * then more and more reluctant, approaching [threshold] × 3 without reaching it. It used to follow
 * the finger exactly and stop dead at 3× — a wall in the middle of a gesture.
 */
internal fun dialogDragResistance(
    travel: Float,
    threshold: Float,
): Float {
    if (travel <= threshold || threshold <= 0f) return travel.coerceAtLeast(0f)
    val reach = threshold * RUBBER_REACH
    val beyond = travel - threshold
    return threshold + reach * (1f - 1f / (beyond * RUBBER_STIFFNESS / reach + 1f))
}

/** The finger travel that draws the panel at [offset]: the inverse of [dialogDragResistance]. */
internal fun dialogDragTravel(
    offset: Float,
    threshold: Float,
): Float {
    if (offset <= threshold || threshold <= 0f) return offset.coerceAtLeast(0f)
    val reach = threshold * RUBBER_REACH
    val beyond = (offset - threshold).coerceAtMost(reach * RUBBER_REACH_LIMIT)
    return threshold + reach / RUBBER_STIFFNESS * (1f / (1f - beyond / reach) - 1f)
}

/** How far past the commit point the panel can ever be drawn, in thresholds. */
private const val RUBBER_REACH = 2f

/** How readily the band gives: lower is stiffer. */
private const val RUBBER_STIFFNESS = 0.55f

/** Keeps the inverse finite at the asymptote. */
private const val RUBBER_REACH_LIMIT = 0.999f

@Stable
internal class DialogDragState(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val dismiss: () -> Unit,
    private val threshold: Float,
    /** Read at release time, not construction: the preference can change while a panel is held. */
    private val reduceMotion: () -> Boolean = { false },
    /**
     * Ticks once when the panel first passes the point where letting go would close it.
     *
     * The commit point is invisible until it has already happened — the panel looks the same on
     * either side of it — which is the same problem pull-to-refresh has, and the same answer:
     * see [RefreshThresholdHaptics]. Re-armed by the release, so a panel held around the
     * threshold does not rattle.
     */
    private val onThresholdCrossed: () -> Unit = {},
    /**
     * Asked instead of [dismiss] when the owner may decline — a form with unsaved input asking
     * 「放弃更改？」 first. A declined release settles the panel home like a short pull.
     */
    private val tryDismiss: (() -> Boolean)? = null,
) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set
    var dismissedByDrag = false
        private set

    // The finger's own travel; [offset] is where that puts the panel.
    private var travel = 0f
    private var crossedThreshold = false
    private var settle: Job? = null

    fun stopSettling() {
        settle?.cancel()
        settle = null
    }

    fun reset() {
        stopSettling()
        offset = 0f
        travel = 0f
        dismissedByDrag = false
        crossedThreshold = false
    }

    fun move(delta: Float): Float {
        if (!enabled() || dismissedByDrag) return 0f
        stopSettling()
        val before = travel
        travel = (travel + delta).coerceAtLeast(0f)
        offset = dialogDragResistance(travel, threshold)
        if (!crossedThreshold && offset >= threshold) {
            crossedThreshold = true
            onThresholdCrossed()
        }
        // The whole of the finger's movement is the panel's, stretched or not: none of it may leak
        // into the content's own scroll.
        return travel - before
    }

    fun release(velocity: Float) {
        if (dismissedByDrag) return
        stopSettling()
        crossedThreshold = false
        if (offset <= 0f) return
        if (enabled() && shouldDismissDialogDrag(offset, velocity, threshold) && accepted()) {
            dismissedByDrag = true
        } else {
            settle =
                scope.launch {
                    animate(
                        offset,
                        0f,
                        velocity.coerceIn(-threshold * 10f, threshold * 10f),
                        // The shared resting-state spring, so 减弱动态效果 cuts the panel home the
                        // way it cuts every other settle. Its slight overshoot never shows: the
                        // clamp below is the panel's resting position.
                        Motion.settle<Float>(reduceMotion()),
                    ) { value, _ ->
                        offset = value.coerceAtLeast(0f)
                        travel = dialogDragTravel(offset, threshold)
                    }
                    offset = 0f
                    travel = 0f
                }
        }
    }

    private fun accepted(): Boolean {
        val ask = tryDismiss ?: return true.also { dismiss() }
        return ask()
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f && offset > 0f) {
            Offset(0f, move(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, move(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        release(available.y)
        return Velocity(0f, available.y)
    }
}

@Composable
internal fun rememberDialogDragState(
    enabled: () -> Boolean,
    dismiss: () -> Unit,
    tryDismiss: (() -> Boolean)? = null,
): DialogDragState {
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDismiss by rememberUpdatedState(dismiss)
    val currentTryDismiss by rememberUpdatedState(tryDismiss)
    val reduceMotion by rememberUpdatedState(LocalAccessibilityOptions.current.reduceMotion)
    val haptics by rememberUpdatedState(LocalHaptics.current)
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    return remember(scope, threshold) {
        DialogDragState(
            scope = scope,
            enabled = { currentEnabled() },
            dismiss = { currentDismiss() },
            threshold = threshold,
            reduceMotion = { reduceMotion },
            onThresholdCrossed = { haptics.play(HapticSignal.Threshold) },
            tryDismiss = { currentTryDismiss?.invoke() ?: true.also { currentDismiss() } },
        )
    }
}

@Composable
internal fun DialogDragHandle(
    state: DialogDragState,
    enabled: Boolean,
) {
    Box(
        Modifier.fillMaxWidth().height(DialogDragHandleHeight).draggable(
            state = rememberDraggableState { state.move(it) },
            orientation = Orientation.Vertical,
            enabled = enabled,
            onDragStopped = { state.release(it) },
        ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.size(32.dp, 4.dp).background(LocalPalette.current.sub.copy(alpha = 0.35f), CircleShape))
    }
}

internal val DialogDragHandleHeight = 28.dp
