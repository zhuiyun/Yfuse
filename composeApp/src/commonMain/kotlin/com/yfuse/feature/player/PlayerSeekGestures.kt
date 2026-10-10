package com.yfuse.feature.player

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** How long after its latest step a run of taps on one side still adds up. */
internal const val DOUBLE_TAP_BURST_WINDOW_MS = 900L

/** 横滑跳转: how far a swipe across the whole picture moves at a crawl… */
internal const val SWIPE_SEEK_SLOW_WINDOW_MS = 90_000L

/** …and at a flick. */
internal const val SWIPE_SEEK_FAST_WINDOW_MS = 180_000L

/** At or under this pace a swipe is placing, and moves by the slow window. */
private const val SWIPE_SLOW_DP_PER_MS = 0.25f

/** At or over this pace it is throwing, and moves by the fast one. */
private const val SWIPE_FAST_DP_PER_MS = 1.5f

/** How much of each new speed sample the pace takes in; the rest is the finger's recent past. */
private const val SWIPE_PACE_SMOOTHING = 0.3f

/** Which third of the picture [x] is in: -1 the rewinding side, 1 the forward side, 0 the middle. */
internal fun pictureThird(
    x: Float,
    width: Int,
): Int =
    when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

/**
 * 双击快进快退, several in a row.
 *
 * A double tap on a side starts a burst of one step. While the burst lasts —
 * [DOUBLE_TAP_BURST_WINDOW_MS] after its latest step — every further tap on the same side adds a step
 * of its own: a single tap, or another double tap, which counts as the two taps it is. Before, only
 * whole double taps added up, so the third tap of a run did nothing and the fourth seeked once.
 */
internal class DoubleTapSeekBurst(
    private val nowMs: () -> Long = monotonicMillis(),
) {
    /** -1 rewinding, 1 fast-forwarding; 0 before the first burst. */
    var direction: Int = 0
        private set

    /** How far the burst has moved in all, for the HUD. */
    var totalMs: Long = 0L
        private set

    private var lastStepAtMs: Long? = null

    fun reset() {
        direction = 0
        totalMs = 0L
        lastStepAtMs = null
    }

    /** Whether a tap going [direction] now would add to the running burst. */
    fun continues(direction: Int): Boolean {
        val last = lastStepAtMs ?: return false
        return direction != 0 && this.direction == direction && nowMs() - last < DOUBLE_TAP_BURST_WINDOW_MS
    }

    /** Records [taps] taps going [direction], each worth [stepMs], and returns how far to move for them. */
    fun add(
        direction: Int,
        stepMs: Long,
        taps: Int,
    ): Long {
        val continuing = continues(direction)
        val moved = if (continuing) stepMs * taps else stepMs
        totalMs = if (continuing) totalMs + moved else moved
        this.direction = direction
        lastStepAtMs = nowMs()
        return moved
    }
}

/**
 * How far a step of a sideways swipe moves the position, given the step as a fraction of the
 * picture's width and the finger's pace.
 *
 * A whole width is [SWIPE_SEEK_SLOW_WINDOW_MS] at a crawl and [SWIPE_SEEK_FAST_WINDOW_MS] at a flick,
 * never more than the whole item. It used to be 45% of the item, so on a two-hour film each dp was
 * about four seconds and a thumb's tremor moved half a minute.
 */
internal fun swipeSeekStepMs(
    widthFraction: Float,
    speedDpPerMs: Float,
    durationMs: Long,
): Long {
    val pace = ((speedDpPerMs - SWIPE_SLOW_DP_PER_MS) / (SWIPE_FAST_DP_PER_MS - SWIPE_SLOW_DP_PER_MS)).coerceIn(0f, 1f)
    val window = SWIPE_SEEK_SLOW_WINDOW_MS + (SWIPE_SEEK_FAST_WINDOW_MS - SWIPE_SEEK_SLOW_WINDOW_MS) * pace
    return (widthFraction * window.coerceAtMost(durationMs.coerceAtLeast(1L).toFloat())).roundToLong()
}

/** One sideways swipe's pace, smoothed over its steps so one jittery sample cannot throw the seek. */
internal class SwipeSeekPace {
    private var speedDpPerMs = 0f

    fun reset() {
        speedDpPerMs = 0f
    }

    /** How far [dxPx] of travel, [dtMs] after the previous step, moves the position; see [swipeSeekStepMs]. */
    fun step(
        dxPx: Float,
        dtMs: Long,
        widthPx: Int,
        density: Float,
        durationMs: Long,
    ): Long {
        val sample = abs(dxPx) / density / dtMs.coerceAtLeast(1L)
        speedDpPerMs += (sample - speedDpPerMs) * SWIPE_PACE_SMOOTHING
        return swipeSeekStepMs(dxPx / widthPx.coerceAtLeast(1), speedDpPerMs, durationMs)
    }
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
