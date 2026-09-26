package com.yfuse.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * 捏合填充 — two fingers on the picture switch it between 适应 and 裁剪填满.
 *
 * The moment a second finger lands, this takes the gesture: every change from then until the last
 * finger lifts is consumed. That is also what cancels whatever one finger had started, because the
 * picture's other detectors already give up on a consumed change — the tap detector drops a pending
 * tap, double tap or long press, and the drag detector abandons its slop wait or its drag without
 * committing a seek. It has to be the last `pointerInput` on the picture for that: the main pass
 * reaches the innermost modifier first. A hold that has already taken effect is not a detector's
 * to cancel, so [onSecondFinger] ends it.
 *
 * [canPinch] is asked with the first finger's position when the second arrives, so the lock and the
 * system gesture strip at the top are judged as the drags judge them. Nothing is consumed when it
 * says no.
 */
internal suspend fun PointerInputScope.detectPinchFill(
    canPinch: (Offset) -> Boolean,
    filled: () -> Boolean,
    onSecondFinger: () -> Unit,
    onFill: (Boolean) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        var pinch: PinchFillTracker? = null
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pinch == null && pressed.size >= 2) {
                if (!canPinch(first.position)) continue
                pinch = PinchFillTracker(startFilled = filled(), startSpan = pressed.fingerSpan())
                onSecondFinger()
            }
            val tracker = pinch ?: continue
            if (pressed.size >= 2) tracker.follow(pressed.fingerSpan())?.let(onFill)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** How far apart the first two fingers down are. */
private fun List<PointerInputChange>.fingerSpan(): Float = (this[0].position - this[1].position).getDistance()
