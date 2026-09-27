package com.yfuse.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuKind
import kotlin.math.abs

/** How far outside a comment's own box a finger still lands on it, in dp: lanes are short. */
internal const val DANMAKU_PICK_SLOP = 10f

/**
 * How far the tap may end from where it went down, in dp. The tap detector already refuses a
 * press that travelled past its slop; this only stops a stale press from claiming a new tap.
 */
internal const val DANMAKU_CLAIM_SLOP = 24f

/**
 * The left edge of a comment [elapsedMs] into its [durationMs], in the comment area's units. The
 * one formula the overlay draws with and a finger is tested against, so the two cannot disagree.
 */
internal fun danmakuLeft(
    kind: DanmakuKind,
    elapsedMs: Long,
    durationMs: Long,
    viewportWidth: Float,
    width: Float,
): Float =
    if (kind == DanmakuKind.Scroll) {
        val progress = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        viewportWidth - (viewportWidth + width) * progress
    } else {
        (viewportWidth - width).coerceAtLeast(0f) / 2f
    }

/** What the overlay has placed, as much of it as a finger needs: dp, in the comment area's frame. */
internal class DanmakuPickLayout(
    val placements: List<DanmakuLanePlacement>,
    val laneHeight: Float,
    val viewportWidth: Float,
    val scrollDurationMs: Long,
    val fixedDurationMs: Long,
) {
    fun durationOf(kind: DanmakuKind): Long = if (kind == DanmakuKind.Scroll) scrollDurationMs else fixedDurationMs
}

/**
 * 点弹幕: one comment stopped where the finger found it, and — once its menu closes — setting off
 * again from there rather than from wherever the clock says it would have got to.
 */
internal data class DanmakuHold(
    val index: Int,
    val comment: DanmakuComment,
    val lane: Int,
    val width: Float,
    val laneHeight: Float,
    val viewportWidth: Float,
    val durationMs: Long,
    /** How far into its flight it was when it stopped. */
    val heldElapsedMs: Long,
    /** The overlay's clock when it set off again; null while it is held. */
    val releasedAtMs: Long? = null,
) {
    val held: Boolean get() = releasedAtMs == null

    val top: Float get() = laneHeight * lane

    fun elapsedAt(renderedMs: Long): Long = releasedAtMs?.let { heldElapsedMs + (renderedMs - it) } ?: heldElapsedMs

    fun leftAt(renderedMs: Long): Float =
        danmakuLeft(comment.kind, elapsedAt(renderedMs).coerceIn(0L, durationMs), durationMs, viewportWidth, width)

    /** Where it sits while held, in the comment area's frame. */
    fun boundsAt(renderedMs: Long): Rect {
        val left = leftAt(renderedMs)
        return Rect(left, top, left + width, top + laneHeight)
    }

    /** Flown off after setting off again — or the clock went back past the moment it did. */
    fun finishedAt(renderedMs: Long): Boolean {
        val releasedAt = releasedAtMs ?: return false
        return renderedMs < releasedAt || elapsedAt(renderedMs) > durationMs
    }

    /** Whether [placement] is this comment, which the overlay then leaves to the hold to draw. */
    fun isFor(placement: DanmakuLanePlacement): Boolean =
        placement.input.index == index &&
            placement.input.comment.timeMs == comment.timeMs &&
            placement.input.comment.text == comment.text
}

/**
 * The comment on screen at [point] when the overlay's clock reads [renderedMs], or null. A point
 * within [slop] of more than one takes the one it is inside, then the one whose middle is nearer.
 */
internal fun DanmakuPickLayout.pick(
    point: Offset,
    renderedMs: Long,
    slop: Float,
): DanmakuHold? {
    var best: DanmakuHold? = null
    var bestOutside = Float.MAX_VALUE
    var bestFromMiddle = Float.MAX_VALUE
    placements.forEach { placement ->
        val comment = placement.input.comment
        val duration = durationOf(comment.kind)
        val elapsed = renderedMs - comment.timeMs
        if (elapsed < 0L || elapsed > duration) return@forEach
        val width = placement.input.width
        val left = danmakuLeft(comment.kind, elapsed, duration, viewportWidth, width)
        val top = laneHeight * placement.lane
        val dx = outside(point.x, left, left + width)
        val dy = outside(point.y, top, top + laneHeight)
        if (dx > slop || dy > slop) return@forEach
        val outside = dx + dy
        val fromMiddle = abs(point.x - (left + width / 2f)) + abs(point.y - (top + laneHeight / 2f))
        if (outside < bestOutside || (outside == bestOutside && fromMiddle < bestFromMiddle)) {
            bestOutside = outside
            bestFromMiddle = fromMiddle
            best =
                DanmakuHold(
                    index = placement.input.index,
                    comment = comment,
                    lane = placement.lane,
                    width = width,
                    laneHeight = laneHeight,
                    viewportWidth = viewportWidth,
                    durationMs = duration,
                    heldElapsedMs = elapsed,
                )
        }
    }
    return best
}

private fun outside(
    value: Float,
    from: Float,
    to: Float,
): Float =
    when {
        value < from -> from - value
        value > to -> value - to
        else -> 0f
    }

/**
 * Where 点弹幕's menu goes beside the comment at [comment]: under it when it fits above [margin]
 * from the bottom of [bounds], over it otherwise, and slid along so it never leaves the sides.
 */
internal fun danmakuMenuPosition(
    comment: Rect,
    menu: Size,
    bounds: Size,
    gap: Float,
    margin: Float,
): Offset {
    val below = comment.bottom + gap
    val y =
        if (below + menu.height <= bounds.height - margin) {
            below
        } else {
            (comment.top - gap - menu.height).coerceAtLeast(margin)
        }
    val x = comment.left.coerceIn(margin, (bounds.width - margin - menu.width).coerceAtLeast(margin))
    return Offset(x, y)
}

/**
 * 点弹幕's state between the overlay, which draws the comments, and the picture's tap, which may
 * claim one. Everything here is in dp in the comment area's own frame; converting a finger into
 * that frame is [DanmakuPicker]'s job.
 *
 * A tap is only known to be a tap once the double-tap window has passed, by which time the comment
 * under it has flown on. So the comment is found when the finger goes down ([press]) and only
 * taken when the tap is confirmed ([claim]); anything else — a double tap, a hold, a drag — never
 * claims it and the press is forgotten at the next one.
 */
@Stable
internal class DanmakuPickState {
    /** Written by the overlay whenever its placements change; read only when a finger lands. */
    var layout: DanmakuPickLayout? = null

    /** The overlay's own clock, which runs smoother than the engine's. */
    var clock: () -> Long = { 0L }

    private var pressed: DanmakuHold? = null
    private var pressedAt: Offset = Offset.Zero

    /** The comment stopped under a finger, then flying on after its menu closes; null otherwise. */
    var hold: DanmakuHold? by mutableStateOf(null)
        private set

    /** Whether a comment is stopped with its menu open. */
    val menuOpen: Boolean get() = hold?.held == true

    /** A finger went down at [point]: remembers the comment under it, if there is one. */
    fun press(point: Offset) {
        pressed = layout?.pick(point, clock(), DANMAKU_PICK_SLOP)
        pressedAt = point
    }

    /**
     * The tap that ended at [point] turned out to be a single tap: true, and the comment stops,
     * when it went down on one that is still on screen. False leaves the tap to do what it did.
     */
    fun claim(point: Offset): Boolean {
        val candidate = pressed ?: return false
        pressed = null
        if ((point - pressedAt).getDistance() > DANMAKU_CLAIM_SLOP) return false
        val elapsed = clock() - candidate.comment.timeMs
        if (elapsed < 0L || elapsed > candidate.durationMs) return false
        hold = candidate.copy(heldElapsedMs = elapsed, releasedAtMs = null)
        return true
    }

    /** Closes the menu: the comment sets off again from where it stopped. */
    fun release() {
        val current = hold ?: return
        if (current.held) hold = current.copy(releasedAtMs = clock())
    }

    /** Forgets the comment at once — the list it came from has changed. */
    fun drop() {
        pressed = null
        hold = null
    }

    /** Called from the overlay's frame loop: lets go of a comment that has flown off. */
    fun settle(renderedMs: Long) {
        if (hold?.finishedAt(renderedMs) == true) hold = null
    }
}
