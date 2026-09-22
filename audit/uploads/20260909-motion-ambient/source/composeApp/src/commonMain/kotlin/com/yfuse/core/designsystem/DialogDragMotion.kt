package com.yfuse.core.designsystem

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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

@Stable
internal class DialogDragState(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val dismiss: () -> Unit,
    private val threshold: Float,
) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set
    var dismissedByDrag = false
        private set
    private var settle: Job? = null

    fun stopSettling() {
        settle?.cancel()
        settle = null
    }

    fun reset() {
        stopSettling()
        offset = 0f
        dismissedByDrag = false
    }

    fun move(delta: Float): Float {
        if (!enabled() || dismissedByDrag) return 0f
        stopSettling()
        val before = offset
        offset = (offset + delta).coerceIn(0f, threshold * 3f)
        return offset - before
    }

    fun release(velocity: Float) {
        if (dismissedByDrag) return
        stopSettling()
        if (offset <= 0f) return
        if (enabled() && shouldDismissDialogDrag(offset, velocity, threshold)) {
            dismissedByDrag = true
            dismiss()
        } else {
            settle =
                scope.launch {
                    animate(
                        offset,
                        0f,
                        velocity.coerceIn(-threshold * 10f, threshold * 10f),
                        spring(dampingRatio = 1f, stiffness = 450f),
                    ) { value, _ ->
                        offset = value.coerceAtLeast(0f)
                    }
                    offset = 0f
                }
        }
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
): DialogDragState {
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDismiss by rememberUpdatedState(dismiss)
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    return remember(scope, threshold) { DialogDragState(scope, { currentEnabled() }, { currentDismiss() }, threshold) }
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
