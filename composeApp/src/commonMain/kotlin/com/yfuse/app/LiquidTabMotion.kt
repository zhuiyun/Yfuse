package com.yfuse.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.yfuse.core.designsystem.DRAG_PROJECTION_MS
import com.yfuse.core.designsystem.DragProgress
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.Motion
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal fun draggedTabIndex(
    index: Float,
    delta: Float,
    width: Float,
    count: Int,
    rtl: Boolean,
): Float =
    if (width <= 0f || count <= 0) {
        index
    } else {
        (index + delta / width * count * if (rtl) -1f else 1f).coerceIn(0f, (count - 1).toFloat())
    }

/**
 * The tab a capsule let go at [index] settles on, moving at [velocity] cells a second: where it
 * would coast to over [DRAG_PROJECTION_MS] — the projection every other drag in the app decides on
 * — rounded to the nearest cell. A flick carries it on to the next tab where rounding would have
 * pulled it back; the coast is held to one cell, so even a hard flick never skips a tab unseen.
 */
internal fun releasedTabIndex(
    index: Float,
    velocity: Float,
    count: Int,
): Int {
    if (count <= 0) return 0
    val speed = if (velocity.isFinite()) velocity else 0f
    val coast = DragProgress(offset = index, velocity = speed, extent = 1f).project(DRAG_PROJECTION_MS) - index
    return (index + coast.coerceIn(-1f, 1f)).roundToInt().coerceIn(0, count - 1)
}

/** A capsule whose finger has not moved for this long is let go of standing still. */
private val CapsuleStillAfter = 40.milliseconds

internal class LiquidTabMotion(
    val left: State<Float>,
    val right: State<Float>,
    val sweep: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val gestures: Modifier,
    val dragging: Boolean,
)

@Composable
internal fun rememberLiquidTabMotion(
    selected: Int,
    count: Int,
    reduceMotion: Boolean,
    onSelect: (Int) -> Unit,
): LiquidTabMotion {
    var dragIndex by remember { mutableStateOf<Float?>(null) }
    var release by remember { mutableStateOf(0) }
    val currentSelection by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val haptics = LocalHaptics.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // While 搜索 is open the pill is hidden (selected < 0). It used to glide to 首页 as it faded,
    // then cross the whole bar from there to wherever the person went next. It stays put while
    // hidden, and comes back already at the new tab: the bar fades it in, nothing slides.
    val lastShown = remember { intArrayOf(selected.coerceAtLeast(0)) }
    if (selected >= 0) lastShown[0] = selected
    val wasHidden = remember { booleanArrayOf(selected < 0) }
    val reappearing = selected >= 0 && wasHidden[0]
    SideEffect { wasHidden[0] = selected < 0 }
    val target = dragIndex ?: lastShown[0].toFloat()
    var previousTarget by remember { mutableStateOf(target) }
    var previousDirection by remember { mutableStateOf(true) }
    val rightward = if (target == previousTarget) previousDirection else target > previousTarget
    SideEffect {
        previousTarget = target
        previousDirection = rightward
    }
    // The leading edge follows the finger; the tail retains a little weight. On release both
    // settle more slowly, allowing the stretched lens to become round again.
    val left =
        animateFloatAsState(
            target + 0.09f,
            Motion.liquidTabEdge(reduceMotion || reappearing, dragging = dragIndex != null, leading = !rightward),
            label = "liquidTabLeft",
        )
    val right =
        animateFloatAsState(
            target + 0.91f,
            Motion.liquidTabEdge(reduceMotion || reappearing, dragging = dragIndex != null, leading = rightward),
            label = "liquidTabRight",
        )
    val sweep = remember { Animatable(1f) }
    var previousSelection by remember { mutableStateOf(selected) }
    var previousRelease by remember { mutableStateOf(release) }
    LaunchedEffect(selected, release, dragIndex != null, reduceMotion) {
        val changed = selected != previousSelection || release != previousRelease
        previousSelection = selected
        previousRelease = release
        sweep.snapTo(1f)
        if (changed && selected >= 0 && dragIndex == null && !reduceMotion) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(Motion.TAB_SWEEP, delayMillis = Motion.TAB_SWEEP_DELAY, easing = Motion.Curve))
        }
    }
    val gestures =
        Modifier.pointerInput(selected, count, rtl) {
            // The finger's speed along the bar, so letting go on the move carries the capsule on
            // (MO5): the nearest cell alone used to send a flicked capsule back where it came from.
            val velocity = VelocityTracker()
            var lastMove: TimeSource.Monotonic.ValueTimeMark? = null
            try {
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocity.resetTracking()
                        lastMove = null
                        if (currentSelection >= 0) dragIndex = currentSelection.toFloat()
                    },
                    onHorizontalDrag = { change, amount ->
                        dragIndex?.let {
                            change.consume()
                            velocity.addPosition(change.uptimeMillis, change.position)
                            lastMove = TimeSource.Monotonic.markNow()
                            dragIndex = draggedTabIndex(it, amount, size.width.toFloat(), count, rtl)
                        }
                    },
                    onDragEnd = {
                        val moving = lastMove?.let { it.elapsedNow() < CapsuleStillAfter } == true
                        val pixels = if (moving) velocity.calculateVelocity().x else 0f
                        val cells =
                            if (size.width > 0) pixels / size.width * count * (if (rtl) -1f else 1f) else 0f
                        val destination = dragIndex?.let { releasedTabIndex(it, cells, count) }
                        dragIndex = null
                        if (destination != null) {
                            release++
                            // Returning to the same cell does not invoke the tab's scroll-to-top action.
                            if (destination != currentSelection) {
                                // The same tick a tap on the tab gives; a drag used to switch silently.
                                haptics.play(HapticSignal.Select)
                                select(destination)
                            }
                        }
                    },
                    onDragCancel = { dragIndex = null },
                )
            } finally {
                dragIndex = null
            }
        }
    return LiquidTabMotion(left, right, sweep, gestures, dragIndex != null)
}
