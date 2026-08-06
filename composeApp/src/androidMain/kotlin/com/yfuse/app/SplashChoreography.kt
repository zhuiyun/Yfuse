package com.yfuse.app

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.yfuse.core.designsystem.SplashAnimation
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * One complete launch choreography, drawn from a single linear clock.
 *
 * Implementations own their whole timeline — the shell in [AnimatedSplashApp] only runs the
 * clock, paints the background and cross-fades to the app, so a new variant is a new object
 * here plus an entry in [SplashAnimation]. Everything is read from draw-phase lambdas, so a
 * variant must never need recomposition to advance.
 */
internal interface SplashChoreography {

    /** Total run time including the cross-fade out. */
    val durationMs: Float

    /** Where the choreography ends and the cross-fade to the app begins. */
    val fadeStartMs: Float

    /** Draws the mark for [nowMs] into a square canvas. */
    fun DrawScope.drawMark(nowMs: Float)

    /** 0..1 settle progress of the "Yfuse" wordmark. */
    fun wordmark(nowMs: Float): Float

    /** 0..1 settle progress of the tagline. */
    fun tagline(nowMs: Float): Float
}

internal val SplashAnimation.choreography: SplashChoreography
    get() = when (this) {
        SplashAnimation.One -> SplashOne
        SplashAnimation.Two -> SplashTwo
    }

// ---- Shared easing and spring maths. ----

/** 0 before [start], 1 after [start] + [duration], linear in between. */
internal fun span(nowMs: Float, start: Float, duration: Float): Float =
    ((nowMs - start) / duration).coerceIn(0f, 1f)

internal fun smooth(value: Float): Float = value * value * (3f - 2f * value)

internal fun easeOutCubic(value: Float): Float = 1f - (1f - value) * (1f - value) * (1f - value)

internal fun easeOutBack(value: Float): Float {
    val shifted = value - 1f
    return 1f + BackCubic * shifted * shifted * shifted + BackOvershoot * shifted * shifted
}

/** Rises to 1 at the middle of the window and returns to 0 — a single soft pulse. */
internal fun bell(value: Float): Float = sin(value * PiF).coerceAtLeast(0f)

internal fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/**
 * Width gained for height lost.
 *
 * Textbook squash preserves area outright — `scaleX = 1 / scaleY`. That holds at the timid
 * amplitudes nobody can see, but at the amplitude a squash actually needs to read on a phone it
 * throws the silhouette clean out of the canvas: a 21% squash with the crown boost on top comes
 * out 47% wider and gets clipped to a straight edge. Damping the exponent keeps the volume cue
 * and stays inside the box.
 */
internal fun squashWidth(scaleY: Float): Float = (1f / scaleY).pow(SquashWidthGain)

private const val SquashWidthGain = 0.55f

/**
 * One damped oscillation, shaped for squash and stretch.
 *
 * Given a 0..1 progress it returns a first swing of 1, then a smaller swing of the opposite
 * sign, then smaller again, reaching exactly 0 at progress 1 so nothing snaps when the window
 * closes. A plain sine bell only ever compresses — the shape sags and returns, and never reads
 * as elastic. Overshooting past rest into a stretch is what makes it bounce. [cycles] is how
 * many swings fit in the window, [damping] how fast they die out.
 */
internal class Jelly(private val cycles: Float, private val damping: Float) {
    private val tail = exp(-damping)
    private val range = 1f - tail

    /**
     * First-peak height of [raw], so the amplitude constants at the call sites read as real
     * fractions of the mark instead of arbitrary numbers.
     */
    private val normaliser = run {
        val window = (0.5f / cycles).coerceAtMost(1f)
        var peak = 0f
        repeat(24) { peak = max(peak, raw(window * (it + 1) / 24f)) }
        peak.coerceAtLeast(1e-4f)
    }

    operator fun invoke(progress: Float): Float =
        if (progress <= 0f || progress >= 1f) 0f else raw(progress) / normaliser

    // Subtracting the tail value pins the envelope to exactly 0 at progress 1.
    private fun raw(progress: Float): Float =
        sin(progress * cycles * Tau) * ((exp(-damping * progress) - tail) / range)
}

/**
 * Deterministic pseudo-random in 0..1 for [index] and [salt]. Scatter has to survive a redraw
 * unchanged — a real RNG would reshuffle the spray on every frame.
 */
internal fun scatter(index: Int, salt: Int): Float {
    val hashed = (index * 73_856_093) xor (salt * 19_349_663)
    return ((hashed and 0xFFFF) / 65_535f)
}

internal val PiF = PI.toFloat()
internal val Tau = (2.0 * PI).toFloat()
private const val BackOvershoot = 1.70158f
private const val BackCubic = BackOvershoot + 1f
