package com.yfuse.feature.handoff

import com.yfuse.core.designsystem.DragAxis
import com.yfuse.core.designsystem.DragProgress
import com.yfuse.core.designsystem.resolveDragAxis
import com.yfuse.watch.protocol.RemoteControlKey

/** A held swipe starts repeating after this, like a held remote key. */
internal const val REMOTE_REPEAT_DELAY_MS = 400L

/** Then repeats this often: well inside what the relay admits from one phone. */
internal const val REMOTE_REPEAT_INTERVAL_MS = 150L

/**
 * One finger on 遥控器's touchpad, as plain arithmetic. A swipe chooses its axis once it has
 * travelled [slop] ([resolveDragAxis]) and sends one direction: when it has travelled [extent]
 * along that axis, or on release if a flick would have carried it that far ([DragProgress.commits]).
 * Held out past [extent], it repeats that direction after [repeatDelayMs] every
 * [repeatIntervalMs]; pulled back inside, it stops. A touch that never travels [slop] is a tap: OK.
 */
internal class RemoteSwipe(
    private val slop: Float,
    private val extent: Float,
    private val repeatDelayMs: Long = REMOTE_REPEAT_DELAY_MS,
    private val repeatIntervalMs: Long = REMOTE_REPEAT_INTERVAL_MS,
) {
    private var axis = DragAxis.Undecided
    private var dx = 0f
    private var dy = 0f
    private var sent: RemoteControlKey? = null
    private var repeatAtMs: Long? = null

    /** The finger is ([x], [y]) from where it went down at [nowMs]; the key to send now, if any. */
    fun move(
        x: Float,
        y: Float,
        nowMs: Long,
    ): RemoteControlKey? {
        dx = x
        dy = y
        if (axis == DragAxis.Undecided) axis = resolveDragAxis(x, y, slop)
        val key = direction() ?: return null
        val held = sent
        // One direction per swipe: turning back past the other edge sends and repeats nothing.
        if (key != (held ?: key) || progress(key, velocity = 0f).fraction < 1f) {
            repeatAtMs = null
            return null
        }
        if (held == null) {
            sent = key
            repeatAtMs = nowMs + repeatDelayMs
            return key
        }
        if (repeatAtMs == null) repeatAtMs = nowMs + repeatDelayMs
        return hold(nowMs)
    }

    /** The finger rests; the held direction again when a repeat is due. */
    fun hold(nowMs: Long): RemoteControlKey? {
        val due = repeatAtMs ?: return null
        if (nowMs < due) return null
        repeatAtMs = nowMs + repeatIntervalMs
        return sent
    }

    /** The finger lifted, moving at ([vx], [vy]) px/s; the key this touch still owes, if any. */
    fun release(
        vx: Float,
        vy: Float,
    ): RemoteControlKey? {
        if (axis == DragAxis.Undecided) return RemoteControlKey.Center
        if (sent != null) return null
        val key = direction() ?: return null
        val velocity = if (axis == DragAxis.Horizontal) vx else vy
        val towards = if (key == RemoteControlKey.Right || key == RemoteControlKey.Down) velocity else -velocity
        return key.takeIf { progress(it, towards).commits(threshold = 1f) }
    }

    private fun direction(): RemoteControlKey? =
        when (axis) {
            DragAxis.Horizontal -> if (dx >= 0f) RemoteControlKey.Right else RemoteControlKey.Left
            DragAxis.Vertical -> if (dy >= 0f) RemoteControlKey.Down else RemoteControlKey.Up
            DragAxis.Undecided -> null
        }

    private fun progress(
        key: RemoteControlKey,
        velocity: Float,
    ): DragProgress {
        val travelled =
            when (key) {
                RemoteControlKey.Right -> dx
                RemoteControlKey.Left -> -dx
                RemoteControlKey.Down -> dy
                else -> -dy
            }
        return DragProgress(offset = travelled, velocity = velocity, extent = extent)
    }
}
