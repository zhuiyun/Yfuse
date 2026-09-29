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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * 底栏 · 液态分离 — 搜索 is one body with the tabs until the dock opens out, and grows out of the
 * capsule as it does.
 *
 * The dock arrives as one capsule across the whole row. As it settles, its right end swells, draws
 * out a neck and lets go of a drop that lands as the round 搜索 key, while the four tabs close up
 * behind the retreating capsule; the magnifier comes into focus last.
 *
 * Collapsed under a scroll, the dock is one key, 搜索 included: 搜索 first flows back into the
 * capsule's end — the two bridge before they touch — and the capsule then contracts to the key.
 * Expanding again, the key spreads back across the row with 搜索 inside it and lets it go exactly as
 * it did on arrival. A move overtaken by the opposite one runs back the way it came.
 *
 * The dock still leaves with the page as it always did.
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

    /** Centre of the capsule's left cap, and of the collapsed key. */
    val capsuleStart: Float get() = radius

    /** Centre of the capsule's right cap at rest. */
    val capsuleRest: Float get() = width - height - gap - radius

    /** Centre of the capsule's right cap when it spans the whole row, 搜索's place included. */
    val capsuleFull: Float get() = width - radius

    val searchCenter: Float get() = width - radius

    /** The smooth union's width, k: 40 dp at the 62 dp dock, and in proportion under 大号文字. */
    val viscosity: Float get() = LiquidMotion.viscosityFor(height)
}

/** The ways the dock changes shape. */
internal enum class DockLiquidMove(
    val durationMs: Int,
) {
    /** Rising onto a root page — after a pushed page closes, and at launch. */
    Enter(DOCK_ENTER_LEAD_MS + DOCK_SPLIT_MS),

    /** Coming back from collapsed: a scroll back up, the top of the page, or a tap on the collapsed key. */
    Expand(DOCK_GATHER_MS + DOCK_SPLIT_MS),

    /** Collapsing under a scroll: 搜索 flows into the capsule, and the capsule contracts to one key. */
    Collapse(DOCK_MERGE_MS + DOCK_CONTRACT_MS),
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
    /** How far the four tabs spread from the capsule's left end: the whole row, or the capsule's right edge. */
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
        DockLiquidMove.Collapse ->
            if (ms < DOCK_MERGE_MS) dockMergeFrame(ms, metrics) else dockContractFrame(ms - DOCK_MERGE_MS, metrics)
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
 * The collapsed key spreading back across the row, [ms] into the gather: its right end sweeps out to
 * 搜索's far edge on the spring the capsule always expanded with, carrying 搜索 tucked inside it.
 * It ends on exactly the first frame of [dockSplitFrame].
 */
internal fun dockGatherFrame(
    ms: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame {
    val sweep = capsuleSweep(ms, DOCK_GATHER_MS)
    return tuckedFrame(lerp(metrics.capsuleStart, metrics.capsuleFull, sweep), tuck = sweep, metrics)
}

/**
 * 搜索 flowing back into the capsule, [ms] into a collapse — the split run the other way: the
 * capsule's end reaches out to 搜索's far edge on the capsule's spring while 搜索 shrinks and backs
 * into it, the two bridged by k before they touch; the magnifier goes first, over 110 ms. It ends on
 * exactly the first frame of [dockContractFrame].
 */
internal fun dockMergeFrame(
    ms: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame {
    val radius = metrics.radius
    // One ease for both, so the gap closes slowly enough for the bridge to show, and the capsule is
    // still when the contraction takes it.
    val merged = inOut(ms / DOCK_MERGE_MS)
    val capsuleEnd = lerp(metrics.capsuleRest, metrics.capsuleFull, merged)
    val dropCenter = lerp(metrics.searchCenter, metrics.searchCenter - DROP_TUCK * radius, merged)
    val dropRadius = radius * lerp(1f, DROP_TUCK, easeIn((ms - 40f) / (DOCK_MERGE_MS - 40f)))
    return DockLiquidFrame(
        capsuleEnd = capsuleEnd,
        dropCenter = dropCenter,
        dropRadius = dropRadius,
        // k bridges the gap in the first 40 ms, before the two touch; as 搜索 sinks into the
        // capsule's end it is held to what keeps the dock its own height, and it lets go over the
        // last 60 ms, so the capsule contracts as a plain capsule.
        blend =
            min(
                metrics.viscosity * smooth(0f, 40f, ms) * (1f - smooth(MERGE_BLEND_OUT, DOCK_MERGE_MS.toFloat(), ms)),
                dockFlushBlend(capsuleEnd, dropCenter, dropRadius, radius),
            ),
        glyph = 1f - smooth(0f, 110f, ms),
        // The four cells spread with the capsule's end, as they closed up behind it in the split.
        tabSpan = capsuleEnd + radius,
        capsuleRight = capsuleEnd + radius,
        dropShift = dropCenter - metrics.searchCenter,
        dropScale = dropRadius / radius,
    )
}

/**
 * The widest k that keeps the smooth union of the capsule (right cap centred at [capsuleEnd]) and a
 * drop no taller than it within the capsule's own height.
 *
 * Two places can rise above it. Over the drop, the capsule's top swells by k/4·(1 − g/k)², g being
 * how far the drop's nearest point is below that top, so k up to g adds nothing. Past the cap's
 * centre the neck's fillet peaks where the two bodies, each grown by k/4, cross; that crossing stays
 * at or under the top for k up to the positive root of a quadratic in k.
 */
internal fun dockFlushBlend(
    capsuleEnd: Float,
    dropCenter: Float,
    dropRadius: Float,
    radius: Float,
): Float {
    val d = dropCenter - capsuleEnd
    var flush = hypot(max(d, 0f), radius) - dropRadius
    if (d > 0f) {
        // Crossing at height h: h² = (R + k/4)² − x², x = A + B·k along the axis from the cap's centre.
        val a = (d * d + (radius - dropRadius) * (radius + dropRadius)) / (2f * d)
        val b = (radius - dropRadius) / (4f * d)
        val quadratic = 1f / 16f - b * b
        val linear = radius / 2f - 2f * a * b
        // Otherwise the grown drop stays inside the grown cap and the two never cross.
        if (quadratic > 1e-6f) {
            val root = (-linear + sqrt(linear * linear + 4f * quadratic * a * a)) / (2f * quadratic)
            flush = min(flush, root)
        }
    }
    return max(flush, 0f)
}

/**
 * The capsule contracting to the collapsed key, [ms] after 搜索 is inside it, on the spring it always
 * collapsed with; 搜索 stays tucked inside its end and comes to rest inside the key.
 */
internal fun dockContractFrame(
    ms: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame {
    val sweep = capsuleSweep(ms, DOCK_CONTRACT_MS)
    return tuckedFrame(lerp(metrics.capsuleFull, metrics.capsuleStart, sweep), tuck = 1f - sweep, metrics)
}

/**
 * A frame with 搜索 wholly inside the capsule: at the centre of the capsule's end, or [tuck] of the
 * way to where the split takes it from, just inside the end of the whole-row capsule.
 */
private fun tuckedFrame(
    capsuleEnd: Float,
    tuck: Float,
    metrics: DockLiquidMetrics,
): DockLiquidFrame {
    val radius = metrics.radius
    val dropCenter = capsuleEnd - DROP_TUCK * radius * tuck
    return DockLiquidFrame(
        capsuleEnd = capsuleEnd,
        dropCenter = dropCenter,
        dropRadius = DROP_TUCK * radius,
        // Inside the capsule any k would only swell its end.
        blend = 0f,
        glyph = 0f,
        // The tabs lie across the whole row, uncovered or covered as the capsule's end sweeps.
        tabSpan = metrics.width,
        capsuleRight = capsuleEnd + radius,
        dropShift = dropCenter - metrics.searchCenter,
        dropScale = DROP_TUCK,
    )
}

/**
 * The capsule's own spring — ζ 0.92, stiffness 360 ≈ (2π × 3.02 Hz)², the one it always expanded and
 * collapsed with — [ms] after it was let go, normalised to land at [landMs].
 */
private fun capsuleSweep(
    ms: Float,
    landMs: Int,
): Float {
    val landed = spring(landMs.toFloat(), CAPSULE_DAMPING, CAPSULE_HZ)
    return (spring(ms, CAPSULE_DAMPING, CAPSULE_HZ) / landed).coerceAtMost(1f)
}

/**
 * The dock's liquid while it changes shape: which move is playing, where it has got to, and the row
 * it is laid along. Read while drawing and laying out, so a playing move recomposes nothing.
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

/** 搜索 flowing back in, before the capsule contracts. */
internal const val DOCK_MERGE_MS = 240

/** The capsule's spring is within a tenth of a dp of the key by now, so landing it here shows no seam. */
internal const val DOCK_CONTRACT_MS = 360

private const val CAPSULE_DAMPING = 0.92f
private const val CAPSULE_HZ = 3.02f

/** k lets go over the merge's last 60 ms. */
private const val MERGE_BLEND_OUT = DOCK_MERGE_MS - 60f

/** How small 搜索's drop is while it is inside the capsule, and how far inside it sits. */
private const val DROP_TUCK = 0.55f
