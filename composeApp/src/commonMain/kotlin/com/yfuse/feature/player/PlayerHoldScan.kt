package com.yfuse.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * 长按扫描换挡 — how fast a held side third of the picture runs along the timeline, as multiples
 * of real time: standing still, 10×, 30×, 60×.
 *
 * The hold used to ignore the finger once it had taken hold. Now a sideways slide shifts gear,
 * counted from the press: towards the way the scan is going is faster, back the other way slower
 * and then still — so the picture can be held on one frame of the preview and let go right there.
 */
internal val HOLD_SCAN_RATES = listOf(0, 10, 30, 60)

/** A hold starts at 10×. */
internal const val HOLD_SCAN_DEFAULT_GEAR = 1

/** Where a hold whose finger has not shifted gear goes after the ramp: 30×, as it always did. */
internal const val HOLD_SCAN_RAMP_GEAR = 2

/** Sideways travel per gear — the same step as 长按中间's. */
internal val HoldScanGearStep: Dp = 44.dp

/** How far past a mark, in steps, a finger heading back has to go before the gear drops. */
private const val HOLD_SCAN_HYSTERESIS = 0.2f

/**
 * The gear for a finger [dragX] pixels right of where the gears are counted from, while the scan
 * runs in [direction] (-1 back, +1 forward), given the gear it is in now. Every [stepPx] towards
 * the scan's direction is one gear up, every step away one down; the starting gear keeps a full
 * step either side, so the drift of a finger that is only holding never shifts. Coming back
 * towards the start, a gear holds until the finger is [HOLD_SCAN_HYSTERESIS] of a step past its
 * mark, the way [speedBoostGearFor] does.
 */
internal fun holdScanGearFor(
    dragX: Float,
    direction: Int,
    stepPx: Float,
    current: Int,
): Int {
    if (direction == 0 || !dragX.isFinite() || !stepPx.isFinite() || stepPx <= 0f) return current
    val steps = dragX * direction.sign / stepPx
    val offset = current - HOLD_SCAN_DEFAULT_GEAR
    val reached = steps.toInt()
    val next =
        if (abs(reached) >= abs(offset)) {
            reached
        } else {
            (steps + offset.sign * HOLD_SCAN_HYSTERESIS).toInt()
        }
    return (HOLD_SCAN_DEFAULT_GEAR + next).coerceIn(0, HOLD_SCAN_RATES.lastIndex)
}

/** How far one tick of [tickMs] moves the playhead in [gear]. */
internal fun holdScanStepMs(
    gear: Int,
    tickMs: Long,
): Long = HOLD_SCAN_RATES.getOrElse(gear) { 0 } * tickMs

/** "停住", "×10", "×30", "×60". */
internal fun holdScanGearLabel(gear: Int): String {
    val rate = HOLD_SCAN_RATES.getOrElse(gear) { 0 }
    return if (rate == 0) "停住" else "×$rate"
}

/** The gesture HUD during a hold: "快进 ×30 · 12:34 / 45:00". */
internal fun holdScanLabel(
    direction: Int,
    gear: Int,
    targetMs: Long,
    spanMs: Long,
): String {
    val verb = if (direction < 0) "快退" else "快进"
    return "$verb ${holdScanGearLabel(gear)} · ${targetMs.asClock()} / ${spanMs.asClock()}"
}

/**
 * One hold's gears: where the slide is counted from and whether the finger has taken over from
 * the ramp. Plain state — the pointer observer and the scan's ticking loop both run on the main
 * thread, and nothing composes from it.
 */
internal class HoldScanGears {
    var gear: Int = HOLD_SCAN_DEFAULT_GEAR
        private set

    private var originX = 0f
    private var fingerX = 0f
    private var shifted = false

    /** A hold took effect with the finger at [x]. */
    fun start(x: Float) {
        gear = HOLD_SCAN_DEFAULT_GEAR
        originX = x
        fingerX = x
        shifted = false
    }

    /** The finger is at [x]. True when that shifted gear. */
    fun follow(
        x: Float,
        direction: Int,
        stepPx: Float,
    ): Boolean {
        fingerX = x
        val next = holdScanGearFor(x - originX, direction, stepPx, gear)
        if (next == gear) return false
        gear = next
        shifted = true
        return true
    }

    /**
     * The ramp a finger that never shifted gets: 10× to 30×. The count moves with it, putting the
     * finger midway through 30×'s holding zone — a still finger's drift then neither drops it back
     * to 10× nor tips it into 60×, while a real slide either way shifts as it would have.
     * True when it applied.
     */
    fun ramp(
        direction: Int,
        stepPx: Float,
    ): Boolean {
        if (shifted || gear != HOLD_SCAN_DEFAULT_GEAR || direction == 0) return false
        gear = HOLD_SCAN_RAMP_GEAR
        val steps = HOLD_SCAN_RAMP_GEAR - HOLD_SCAN_DEFAULT_GEAR + HOLD_SCAN_RAMP_CENTRE
        originX = fingerX - direction.sign * stepPx * steps
        return true
    }
}

/**
 * Past its mark, in steps, the middle of a gear's holding zone: it holds from [HOLD_SCAN_HYSTERESIS]
 * short of the mark to a full step past it.
 */
private const val HOLD_SCAN_RAMP_CENTRE = (1f - HOLD_SCAN_HYSTERESIS) / 2f
