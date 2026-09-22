package com.yfuse.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.yfuse.core.designsystem.Motion
import kotlin.math.roundToInt

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

internal class LiquidTabMotion(
    val left: State<Float>,
    val right: State<Float>,
    val sweep: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val gestures: Modifier,
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
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val target = dragIndex ?: selected.coerceAtLeast(0).toFloat()
    var previousTarget by remember { mutableStateOf(target) }
    var previousDirection by remember { mutableStateOf(true) }
    val rightward = if (target == previousTarget) previousDirection else target > previousTarget
    SideEffect {
        previousTarget = target
        previousDirection = rightward
    }
    // The leading edge follows the finger; the tail retains a little weight. On release both
    // settle more slowly, allowing the stretched lens to become round again.
    val leading = if (dragIndex != null) 700f else 300f
    val trailing = if (dragIndex != null) 240f else 155f
    val left =
        animateFloatAsState(
            target + 0.09f,
            if (reduceMotion) snap() else spring(0.92f, if (rightward) trailing else leading),
            label = "liquidTabLeft",
        )
    val right =
        animateFloatAsState(
            target + 0.91f,
            if (reduceMotion) snap() else spring(0.92f, if (rightward) leading else trailing),
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
            sweep.animateTo(1f, tween(520, delayMillis = 90, easing = Motion.Curve))
        }
    }
    val gestures =
        Modifier.pointerInput(selected, count, rtl) {
            try {
                detectHorizontalDragGestures(
                    onDragStart = { if (currentSelection >= 0) dragIndex = currentSelection.toFloat() },
                    onHorizontalDrag = { change, amount ->
                        dragIndex?.let {
                            change.consume()
                            dragIndex = draggedTabIndex(it, amount, size.width.toFloat(), count, rtl)
                        }
                    },
                    onDragEnd = {
                        val destination = dragIndex?.roundToInt()
                        dragIndex = null
                        if (destination != null) {
                            release++
                            // Returning to the same cell does not invoke the tab's scroll-to-top action.
                            if (destination != currentSelection) select(destination)
                        }
                    },
                    onDragCancel = { dragIndex = null },
                )
            } finally {
                dragIndex = null
            }
        }
    return LiquidTabMotion(left, right, sweep, gestures)
}
