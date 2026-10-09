package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker

/** One finger's travel; scrolling content or choosing another direction rules out a pull. */
internal class ZoomBackPullTracker {
    var down = Offset.Zero
        private set
    var current = Offset.Zero
        private set
    var pressed = false
    var multiTouch = false

    /** This gesture became a pull; its release and its fling are the pull's. */
    var pulled = false
        private set
    val velocity = VelocityTracker()

    private var axis = DragAxis.Undecided
    private var scrolled = false
    private var lastTimeMillis = 0L

    val canPull: Boolean
        get() = pressed && !multiTouch && !pulled && !scrolled && axis == DragAxis.Vertical && current.y > down.y

    fun start(
        position: Offset,
        timeMillis: Long,
    ) {
        down = position
        current = position
        pressed = true
        multiTouch = false
        axis = DragAxis.Undecided
        scrolled = false
        pulled = false
        lastTimeMillis = timeMillis
        velocity.resetTracking()
        velocity.addPosition(timeMillis, position)
    }

    fun move(
        position: Offset,
        timeMillis: Long,
        slop: Float,
    ) {
        current = position
        lastTimeMillis = timeMillis
        velocity.addPosition(timeMillis, position)
        if (axis == DragAxis.Undecided) {
            val dx = position.x - down.x
            val dy = position.y - down.y
            axis = resolveDragAxis(dx, dy, slop, ZOOM_BACK_HORIZONTAL_BIAS)
            if (axis == DragAxis.Vertical && dy <= 0f) axis = DragAxis.Horizontal
        }
    }

    /** Even a partial scroll reaching the top belongs to scrolling until the next finger down. */
    fun onScroll(consumed: Offset) {
        if (consumed != Offset.Zero) scrolled = true
    }

    /** The release velocity must describe the pull alone, without travel before takeover. */
    fun beginPull() {
        pulled = true
        velocity.resetTracking()
        velocity.addPosition(lastTimeMillis, current)
    }
}
