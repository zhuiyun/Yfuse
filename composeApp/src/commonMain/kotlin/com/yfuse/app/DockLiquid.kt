package com.yfuse.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yfuse.core.designsystem.LiquidBody
import com.yfuse.core.designsystem.LiquidClock
import com.yfuse.core.designsystem.LiquidMotion
import com.yfuse.core.designsystem.LiquidMotion.easeIn
import com.yfuse.core.designsystem.LiquidMotion.inOut
import com.yfuse.core.designsystem.LiquidMotion.lerp
import com.yfuse.core.designsystem.LiquidMotion.smooth
import com.yfuse.core.designsystem.LiquidMotion.spring
import com.yfuse.core.designsystem.LiquidPaths

/*
 * 底栏 · 液态分离 — 搜索 grows out of the capsule each time the dock shows.
 *
 * The dock arrives as one capsule across the whole row. As it settles, its right end swells, draws
 * out a neck and lets go of a drop that lands as the round 搜索 key, while the four tabs close up
 * behind the retreating capsule; the magnifier comes into focus last. The same thing happens when a
 * dock that collapsed under a scroll comes back: the capsule first spreads across the row again and
 * takes 搜索 in, then lets it go.
 *
 * Only the showing is liquid. The dock still leaves with the page, and still collapses under a
 * scroll, exactly as it did before — the frames here are for the way back.
 */

/** Where the dock's pieces rest, in dp along its row: the capsule from 0, the gap, then 搜索. */
internal data class DockLiquidMetrics(
    /** The row, from the capsule's left end to 搜索's right edge. */
    val width: Float,
    val height: Float,
    /** Between the capsule and 搜索 — [com.yfuse.core.designsystem.Dimens.tabBarInset]. */
    val gap: Float,
) {
    val radius: Float get() = height / 2f

    /** Centre of the capsule's left cap. */
    val capsuleStart: Float get() = radius

    /** Centre of the capsule's right cap at rest. */
    val capsuleRest: Float get() = width - height - gap - radius

    /** Centre of the capsule's right cap when it spans the whole row, 搜索's place included. */
    val capsuleFull: Float get() = width - radius

    val searchCenter: Float get() = width - radius

    /** The smooth union's width, k: 40 dp at the 62 dp dock, and in proportion under 大号文字. */
    val viscosity: Float get() = LiquidMotion.viscosityFor(height)
}

/** The two ways the dock shows. */
internal enum class DockLiquidMove(
    val durationMs: Int,
) {
    /** Rising onto a root page — after a pushed page closes, and at launch. */
    Enter(DOCK_ENTER_LEAD_MS + DOCK_SPLIT_MS),

    /** Coming back from collapsed: a scroll back up, the top of the page, or a tap on the collapsed key. */
    Expand(DOCK_GATHER_MS + DOCK_SPLIT_MS),
}

/** One frame of the dock's liquid, in dp along the row. */
internal class DockLiquidFrame(
    /** Centre of the capsule's right cap. */
    val capsuleEnd: Float,
    val dropCenter: Float,
    val dropRadius: Float,
    /** k between the capsule and the drop. */
    val blend: Float,
    /** 搜索's magnifier: 0 blurred and gone, 1 in focus. */
    val glyph: Float,
    /** How far the four tabs spread from the capsule's left end: the whole row at first, then its right edge. */
    val tabSpan: Float,
    /** The capsule's right edge. */
    val capsuleRight: Float,
    /** Where the drop is relative to 搜索's resting centre. */
    val dropShift: Float,
    /** The drop's radius as a fraction of 搜索's. */
    val dropScale: Float,
) {
    fun bodies(metrics: DockLiquidMetrics): List<LiquidBody> =
        listOf(
            LiquidBody.capsule(metrics.capsuleStart, capsuleEnd, metrics.radius),
            LiquidBody.drop(dropCenter, dropRadius, blend),
        )
}

internal fun dockLiquidFrame(
    move: DockLiquidMove,
    ms: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame =
    when (move) {
        DockLiquidMove.Enter -> dockSplitFrame(ms - DOCK_ENTER_LEAD_MS, metrics)
        DockLiquidMove.Expand ->
            if (ms < DOCK_GATHER_MS) dockGatherFrame(ms, metrics) else dockSplitFrame(ms - DOCK_GATHER_MS, metrics)
    }

/**
 * 搜索 budding off the capsule, [ms] after the split starts: the capsule's right end retreats from
 * the full row to its resting place (ζ 0.78, 2.3 Hz) while the drop inside it grows from 0.55 of
 * 搜索's radius and is pushed out to 搜索's centre (ζ 0.62, 2.6 Hz, 30 ms later). The neck breaks
 * where the gap reaches k/2; the magnifier focuses from 110 to 300 ms.
 */
internal fun dockSplitFrame(
    ms: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame {
    val radius = metrics.radius
    val capsuleEnd = lerp(metrics.capsuleFull, metrics.capsuleRest, spring(ms, 0.78f, 2.3f))
    val tucked = metrics.searchCenter - DROP_TUCK * radius
    val dropCenter = lerp(tucked, metrics.searchCenter, spring(ms - 30f, 0.62f, 2.6f))
    val dropRadius = radius * lerp(DROP_TUCK, 1f, smooth(0f, 200f, ms))
    return DockLiquidFrame(
        capsuleEnd = capsuleEnd,
        dropCenter = dropCenter,
        dropRadius = dropRadius,
        blend = metrics.viscosity * LiquidMotion.splitViscosity(ms),
        glyph = smooth(110f, 300f, ms),
        tabSpan = capsuleEnd + radius,
        capsuleRight = capsuleEnd + radius,
        dropShift = dropCenter - metrics.searchCenter,
        dropScale = dropRadius / radius,
    )
}

/**
 * The collapsed capsule spreading back across the row, [ms] into the gather: its right end sweeps
 * out to 搜索's far edge on the spring the capsule always expanded with (ζ 0.92, stiffness 360),
 * while 搜索 shrinks and tucks into it, bridged by k as they meet. It ends on exactly the first
 * frame of [dockSplitFrame].
 */
internal fun dockGatherFrame(
    ms: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame {
    val radius = metrics.radius
    val progress = (ms / DOCK_GATHER_MS).coerceIn(0f, 1f)
    val sweep = (spring(ms, GATHER_DAMPING, GATHER_HZ) / GATHER_SWEEP_END).coerceAtMost(1f)
    val capsuleEnd = lerp(metrics.capsuleStart, metrics.capsuleFull, sweep)
    val dropCenter = lerp(metrics.searchCenter, metrics.searchCenter - DROP_TUCK * radius, inOut(progress))
    val dropRadius = radius * lerp(1f, DROP_TUCK, easeIn((ms - 40f) / (DOCK_GATHER_MS - 40f)))
    return DockLiquidFrame(
        capsuleEnd = capsuleEnd,
        dropCenter = dropCenter,
        dropRadius = dropRadius,
        // k bridges the gap as the capsule reaches 搜索, then falls to three tenths once 搜索 is inside
        // the capsule's end — at full strength it would swell the end out of the capsule's height —
        // and lets go over the gather's last 60 ms.
        blend =
            metrics.viscosity * smooth(0f, 60f, ms) * (1f - 0.7f * smooth(130f, 170f, ms)) *
                (1f - smooth(GATHER_BLEND_OUT, GATHER_END, ms)),
        // The magnifier goes as its drop is taken in, not before.
        glyph = 1f - smooth(60f, 160f, ms),
        // The tabs arrive already spread across the row and are uncovered as the capsule sweeps out.
        tabSpan = metrics.width,
        capsuleRight = capsuleEnd + radius,
        dropShift = dropCenter - metrics.searchCenter,
        dropScale = dropRadius / radius,
    )
}

/**
 * The dock's liquid while it shows: which move is playing, where it has got to, and the row it is
 * laid along. Read while drawing and laying out, so a playing move recomposes nothing.
 */
@Stable
internal class DockLiquid(
    armed: DockLiquidMove?,
) {
    val clock = LiquidClock(armed)

    /** The row, as last laid out; null until it has been. */
    var metrics: DockLiquidMetrics? by mutableStateOf(null)

    /** Reused from frame to frame by the row's drawing. */
    val paths = LiquidPaths()

    /** Whether the liquid is drawing the dock's glass, in which case the panes step aside. */
    val drawing: Boolean get() = clock.move != null

    /** The frame to draw now, or null at rest. */
    fun frame(): DockLiquidFrame? {
        val move = clock.move ?: return null
        val metrics = metrics ?: return null
        return dockLiquidFrame(move, clock.elapsed, metrics)
    }
}

/** The dock is already moving when the split starts: it rises for 60 ms as one capsule first. */
internal const val DOCK_ENTER_LEAD_MS = 60

/** Long enough for both springs to come to within about a tenth of a dp of rest: the panes take over below a pixel. */
internal const val DOCK_SPLIT_MS = 560

internal const val DOCK_GATHER_MS = 240

/** The collapse's own spring, stiffness 360 ≈ (2π × 3.02 Hz)², normalised to land at [DOCK_GATHER_MS]. */
private const val GATHER_DAMPING = 0.92f
private const val GATHER_HZ = 3.02f

/** k lets go over the gather's last 60 ms, so the split starts from a plain capsule. */
private const val GATHER_END = DOCK_GATHER_MS * 1f
private const val GATHER_BLEND_OUT = GATHER_END - 60f
private val GATHER_SWEEP_END = spring(DOCK_GATHER_MS.toFloat(), GATHER_DAMPING, GATHER_HZ)

/** How small 搜索's drop is while it is inside the capsule, and how far inside it sits. */
private const val DROP_TUCK = 0.55f
