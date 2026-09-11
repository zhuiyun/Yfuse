package com.yfuse.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/** Only the original DOWN matters: crossing the top later must not steal an app gesture. */
internal fun allowsPlayerDrag(
    originY: Float,
    systemTopPx: Float,
): Boolean = originY.isFinite() && systemTopPx.isFinite() && originY >= systemTopPx.coerceAtLeast(0f)

/**
 * Leave the complete top-origin stream untouched for Android's notification shade. Checking
 * detectDragGestures.onDragStart is too late: that position is already beyond touch slop.
 * No inset is applied to layout, no overlay is inserted, and buttons retain their own handlers.
 */
internal suspend fun PointerInputScope.detectPlayerDragGestures(
    canStart: (Offset) -> Boolean,
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart(down.position)) return@awaitEachGesture
        var overSlop = Offset.Zero
        val start =
            awaitTouchSlopOrCancellation(down.id) { change, amount ->
                change.consume()
                overSlop = amount
            } ?: return@awaitEachGesture
        var completed = false
        try {
            onDragStart(down.position)
            onDrag(start, overSlop)
            val released =
                drag(start.id) { change ->
                    onDrag(change, change.positionChange())
                    change.consume()
                }
            completed = true
            if (released) onDragEnd() else onDragCancel()
        } finally {
            // Rotation, a replacement item or loss of the input node must not commit a seek.
            if (!completed) onDragCancel()
        }
    }
}
