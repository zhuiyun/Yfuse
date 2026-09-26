package com.yfuse.tv.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.yfuse.core.designsystem.Motion
import com.yfuse.tv.focus.AndroidRemoteKeyMapper
import com.yfuse.tv.focus.RemotePhysicalKey
import com.yfuse.tv.focus.TvFocusDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// 焦点视差 — tvOS's focus parallax, as far as a set-top GPU should go: a card that focus moves
// onto starts turned a little, the side focus came from set back, and springs round to face the
// room while one band of light crosses it. Layer transforms and a gradient only, no blur.

// ------------------------------------------------------------------ travel
//
// Plain bookkeeping and arithmetic first, so they can be tested without a screen.

/**
 * The D-pad press moving focus, kept just long enough for the card it lands on to ask which way
 * it came. A focus change says that focus arrived, not from where; the press reaches the root's
 * preview handler before focus moves, so the root writes it down here — see [recordTvFocusTravel].
 */
internal class TvFocusTravel {
    private var direction: TvFocusDirection? = null
    private var repeated = false
    private var pressedAtMs = 0L

    fun press(
        direction: TvFocusDirection,
        repeated: Boolean,
        atMs: Long,
    ) {
        this.direction = direction
        this.repeated = repeated
        pressedAtMs = atMs
    }

    /**
     * Which way a card gaining focus at [nowMs] was reached: by the fresh press that moved focus,
     * or null. A held D-pad's repeats count as null too — a row racing past under the thumb must
     * not turn and flash every card it crosses.
     */
    fun arrival(nowMs: Long): TvFocusDirection? {
        val moved = direction ?: return null
        if (repeated) return null
        return moved.takeIf { nowMs - pressedAtMs in 0L..TvFocusMotion.ARRIVAL_WINDOW_MILLIS }
    }
}

/**
 * Where a card reached by [direction] starts turned, in degrees about its upright axis: the side
 * focus came from set back. Up and down turn nothing — the light still crosses.
 */
internal fun tvParallaxStartDegrees(direction: TvFocusDirection): Float =
    when (direction) {
        // A positive turn sets the right edge back. Arriving from the left, the left one goes.
        TvFocusDirection.Right -> -TvFocusMotion.PARALLAX_DEGREES
        TvFocusDirection.Left -> TvFocusMotion.PARALLAX_DEGREES
        TvFocusDirection.Up,
        TvFocusDirection.Down,
        -> 0f
    }

/**
 * Where the sweep's centre line crosses the middle of a card [width] wide, [progress] through
 * the sweep. It starts wholly off the left edge and ends wholly past the right, however far its
 * [lean] carries the band's ends beyond its middle.
 */
internal fun tvSweepCentre(
    progress: Float,
    width: Float,
    band: Float,
    lean: Float,
): Float {
    val from = -(band / 2f + lean)
    val to = width + band / 2f + lean
    return from + (to - from) * progress
}

// ------------------------------------------------------------------ compose

/** The root's [TvFocusTravel]; null where there is none, and cards then arrive still. */
internal val LocalTvFocusTravel = staticCompositionLocalOf<TvFocusTravel?> { null }

/** Writes down every D-pad press on its way to focus — see [TvFocusTravel]. It consumes nothing. */
internal fun Modifier.recordTvFocusTravel(travel: TvFocusTravel): Modifier =
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == KeyEvent.ACTION_DOWN) {
            native.focusDirection()?.let { direction ->
                travel.press(direction, repeated = native.repeatCount > 0, atMs = native.eventTime)
            }
        }
        false
    }

private fun KeyEvent.focusDirection(): TvFocusDirection? =
    when (AndroidRemoteKeyMapper.physicalKey(keyCode)) {
        RemotePhysicalKey.DirectionUp -> TvFocusDirection.Up
        RemotePhysicalKey.DirectionDown -> TvFocusDirection.Down
        RemotePhysicalKey.DirectionLeft -> TvFocusDirection.Left
        RemotePhysicalKey.DirectionRight -> TvFocusDirection.Right
        else -> null
    }

/**
 * One card's 焦点视差: the turn it arrives with and the light that crosses it. Both are read only
 * in the layer and draw phases, so a card arriving recomposes nothing.
 */
@Stable
internal class TvFocusParallax(
    private val scope: CoroutineScope,
) {
    /** Degrees about the card's upright axis; 0 at rest. */
    val tilt = Animatable(0f)

    /** How far across the card the light is, 0 to 1; at rest at 1, which draws nothing. */
    val sweep = Animatable(1f)

    /** Focus has just arrived, travelling [direction]. */
    fun arrive(direction: TvFocusDirection) {
        val start = tvParallaxStartDegrees(direction)
        if (start != 0f) {
            scope.launch {
                tilt.snapTo(start)
                tilt.animateTo(0f, TvFocusMotion.parallax())
            }
        }
        scope.launch {
            sweep.snapTo(0f)
            sweep.animateTo(1f, Motion.tween(TvFocusMotion.SWEEP_MILLIS, easing = TvFocusMotion.SweepEasing))
        }
    }

    /** Focus has arrived now, if a D-pad press sent it here: see [TvFocusTravel.arrival]. */
    fun arriveBy(travel: TvFocusTravel?) {
        travel?.arrival(SystemClock.uptimeMillis())?.let(::arrive)
    }
}

@Composable
internal fun rememberTvFocusParallax(): TvFocusParallax {
    val scope = rememberCoroutineScope()
    return remember(scope) { TvFocusParallax(scope) }
}

/**
 * The sweep's band for a card of [size]: one gradient, built once the size is known and slid
 * across the card frame by frame.
 */
internal class TvFocusSweep(
    size: Size,
) {
    val width = size.width
    val band = size.width * TvFocusMotion.SWEEP_BAND
    val lean = size.height / 2f * tan(TvFocusMotion.SWEEP_LEAN_DEGREES * DEGREES_TO_RADIANS)

    // The stripes of a linear gradient run square to its line, so a line turned by the lean gives
    // a band whose top runs ahead of its foot, centred on the card's middle at x = 0.
    val brush: Brush =
        run {
            val angle = TvFocusMotion.SWEEP_LEAN_DEGREES * DEGREES_TO_RADIANS
            val half = band / 2f
            val middle = size.height / 2f
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = TvFocusMotion.SWEEP_ALPHA),
                1f to Color.Transparent,
                start = Offset(-half * cos(angle), middle - half * sin(angle)),
                end = Offset(half * cos(angle), middle + half * sin(angle)),
            )
        }
}

/** The light [progress] of the way across the card; nothing before it starts or once it has gone. */
internal fun DrawScope.drawTvFocusSweep(
    sweep: TvFocusSweep,
    progress: Float,
) {
    if (progress <= 0f || progress >= 1f) return
    val centre = tvSweepCentre(progress, sweep.width, sweep.band, sweep.lean)
    // The gradient travels with the translation; the rectangle it fills is moved back onto the card.
    translate(left = centre) {
        drawRect(sweep.brush, topLeft = Offset(-centre, 0f), size = size)
    }
}

private const val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()
