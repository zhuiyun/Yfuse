package com.yfuse.core.designsystem

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.navigationevent.NavigationEvent

/** One Activity callback; the real start event is forwarded before any progress arrives. */
internal class NavigationBackCallback(
    private val acceptsBack: () -> Boolean,
    private val onStarted: (NavigationEvent) -> Unit,
    private val onProgressed: (NavigationEvent) -> Unit,
    private val onCompleted: () -> Unit,
    private val onCancelled: () -> Unit,
) : OnBackPressedCallback(false) {
    private var generation = 0
    private var gestureGeneration: Int? = null

    fun updateEnabled(enabled: Boolean) {
        if (!enabled) invalidateGesture()
        isEnabled = enabled
    }

    /** Reject this gesture's late completion even if the route becomes visible again. */
    fun invalidateGesture() {
        generation++
        rejectGesture()
    }

    private fun rejectGesture() {
        val gesture = gestureGeneration
        if (gesture != null && gesture != REJECTED) {
            gestureGeneration = REJECTED
            onCancelled()
        }
    }

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        rejectGesture()
        gestureGeneration = if (acceptsBack()) generation else REJECTED
        if (gestureGeneration != REJECTED) onStarted(backEvent.navigationEvent())
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (!acceptsBack() || gestureGeneration != generation) rejectGesture()
        if (gestureGeneration == generation) onProgressed(backEvent.navigationEvent())
    }

    override fun handleOnBackPressed() {
        val gesture = gestureGeneration
        gestureGeneration = null
        when {
            gesture == null && acceptsBack() -> onCompleted()
            gesture != REJECTED && gesture == generation && acceptsBack() -> onCompleted()
            gesture != null && gesture != REJECTED -> onCancelled()
        }
    }

    override fun handleOnBackCancelled() {
        val gesture = gestureGeneration
        gestureGeneration = null
        if (gesture != null && gesture != REJECTED) onCancelled()
    }

    private companion object {
        const val REJECTED = -1
    }
}

private fun BackEventCompat.navigationEvent(): NavigationEvent =
    NavigationEvent(
        touchX = touchX,
        touchY = touchY,
        progress = progress.coerceIn(0f, 1f),
        swipeEdge = swipeEdge,
    )
