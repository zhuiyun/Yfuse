package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A drag measured against the distance it has to cover to commit: where the finger is, how fast
 * it is going, and where it would coast to if let go. Every gesture that follows a finger and then
 * has to decide — 跟手返回, a swiped row — decides through this, so they all decide alike.
 *
 * The decision is made on the *projected* position rather than the current one (WWDC 2018,
 * "Designing Fluid Interfaces"): a short flick counts for the distance it would have carried,
 * so a quick flick commits and a slow drag to the same place does not.
 */
@Immutable
data class DragProgress(
    /** Pixels travelled along the axis that commits, signed towards committing. */
    val offset: Float,
    /** Pixels per second along the same axis, signed the same way. */
    val velocity: Float,
    /** Pixels from rest to progress 1. */
    val extent: Float,
) {
    /** 0 at rest, 1 at [extent]; not clamped, so an overshoot reads as more than 1. */
    val fraction: Float get() = if (extent > 0f) offset / extent else 0f

    /** Where the finger would be [millis] from now if it kept its current speed. */
    fun project(millis: Float): Float = offset + velocity * millis / 1_000f

    /** [project] as a fraction of [extent]. */
    fun projectedFraction(millis: Float): Float = if (extent > 0f) project(millis) / extent else 0f

    /** Whether letting go now commits: the projected position is past [threshold] of the way. */
    fun commits(
        threshold: Float,
        projectionMillis: Float = DRAG_PROJECTION_MS,
    ): Boolean = projectedFraction(projectionMillis) > threshold
}

/** How far ahead a release is projected: long enough to credit a flick, short enough not to invent one. */
const val DRAG_PROJECTION_MS = 170f

/** iOS's rubber-band constant. */
const val RUBBER_BAND_COEFFICIENT = 0.55f

/**
 * iOS's rubber band: past a limit the content follows ever more reluctantly and never quite
 * stops. [overshoot] is how far past the limit the finger is; [dimension] is the size the
 * resistance is measured against. The result never reaches [dimension], whatever the overshoot.
 */
fun rubberBand(
    overshoot: Float,
    dimension: Float,
    coefficient: Float = RUBBER_BAND_COEFFICIENT,
): Float {
    if (dimension <= 0f || overshoot == 0f || !overshoot.isFinite()) return 0f
    val sign = if (overshoot < 0f) -1f else 1f
    val x = abs(overshoot)
    return sign * (1f - 1f / (x * coefficient / dimension + 1f)) * dimension
}

/** Which way a drag has been decided to go, once it has moved far enough to say. */
enum class DragAxis { Undecided, Horizontal, Vertical }

/**
 * A drag's axis, once it has travelled [slop]. Horizontal only when |dx| is more than
 * [horizontalBias] times |dy|, so a slightly diagonal scroll stays a scroll; the caller locks
 * the answer for the rest of the gesture rather than asking again.
 *
 * 1.2 is the list rows' bias (5.4); 跟手返回 wants vertical whenever |dy| > 0.8 |dx|, which is a
 * horizontal bias of 1.25.
 */
fun resolveDragAxis(
    dx: Float,
    dy: Float,
    slop: Float,
    horizontalBias: Float = 1.2f,
): DragAxis {
    if (!dx.isFinite() || !dy.isFinite() || hypot(dx, dy) < slop) return DragAxis.Undecided
    return if (abs(dx) > horizontalBias * abs(dy)) DragAxis.Horizontal else DragAxis.Vertical
}

/**
 * Whether a drag that began [startX] from the left of a [width]-wide window belongs to the
 * system's back gesture: within [edge] of either side, no horizontal gesture of ours may start.
 */
fun inSystemBackEdge(
    startX: Float,
    width: Float,
    edge: Float,
): Boolean = startX < edge || startX > width - edge
