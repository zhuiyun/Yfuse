package com.yfuse.core.designsystem

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rectangle drawn turned about its own centre, in one window's pixels.
 *
 * [width] and [height] are measured in the rectangle's own frame, before [rotation] (degrees,
 * clockwise — the sense of `graphicsLayer.rotationZ`) turns it about [center].
 */
@Immutable
internal data class HandoffBox(
    val center: Offset,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val corner: Float = 0f,
) {
    fun scaled(factor: Float): HandoffBox = copy(width = width * factor, height = height * factor)

    fun shifted(by: Offset): HandoffBox = copy(center = center + by)

    companion object {
        fun of(
            rect: Rect,
            corner: Float = 0f,
        ): HandoffBox = HandoffBox(rect.center, rect.width, rect.height, 0f, corner)
    }
}

internal fun mixFloat(
    from: Float,
    to: Float,
    fraction: Float,
): Float = from + (to - from) * fraction

/** Mixes position, size and corner by [move]; the turn follows its own [turn] progress. */
internal fun lerpBox(
    from: HandoffBox,
    to: HandoffBox,
    move: Float,
    turn: Float = move,
): HandoffBox =
    HandoffBox(
        center = Offset(mixFloat(from.center.x, to.center.x, move), mixFloat(from.center.y, to.center.y, move)),
        width = mixFloat(from.width, to.width, move),
        height = mixFloat(from.height, to.height, move),
        rotation = mixFloat(from.rotation, to.rotation, turn),
        corner = mixFloat(from.corner, to.corner, move),
    )

/**
 * A display as one window sees it: the quarter turns of `Display.rotation`, the display's size in
 * that rotation, and where the window's own origin sits on it.
 */
@Immutable
internal data class ScreenGeometry(
    val rotation: Int,
    val size: Size,
    val windowOffset: Offset = Offset.Zero,
) {
    /** The display in its natural orientation, the frame both windows share. */
    val natural: Size
        get() = if (rotation.mod(2) == 1) Size(size.height, size.width) else size
}

/** A point on a display rotated by [rotation] quarter turns, in the display's natural frame. */
internal fun toNatural(
    point: Offset,
    rotation: Int,
    natural: Size,
): Offset =
    when (rotation.mod(4)) {
        1 -> Offset(natural.width - point.y, point.x)
        2 -> Offset(natural.width - point.x, natural.height - point.y)
        3 -> Offset(point.y, natural.height - point.x)
        else -> point
    }

/** The inverse of [toNatural]. */
internal fun fromNatural(
    point: Offset,
    rotation: Int,
    natural: Size,
): Offset =
    when (rotation.mod(4)) {
        1 -> Offset(point.y, natural.width - point.x)
        2 -> Offset(natural.width - point.x, natural.height - point.y)
        3 -> Offset(natural.height - point.y, point.x)
        else -> point
    }

/**
 * How far a drawing in the target window has to be turned to look like the source did.
 *
 * Content drawn upright in a window rotated by `r` quarter turns sits turned `90·r` degrees on
 * the glass, so matching the source takes `90·(source − target)`, folded into (−180, 180].
 */
internal fun handoffTurnDegrees(
    sourceRotation: Int,
    targetRotation: Int,
): Float =
    when ((sourceRotation - targetRotation).mod(4)) {
        1 -> 90f
        2 -> 180f
        3 -> -90f
        else -> 0f
    }

/**
 * The same patch of glass seen from another window, in that window's screen pixels.
 *
 * The player is a separate, landscape Activity: a phone turns a quarter between the two windows,
 * so a rectangle cannot be carried across by proportion — a full-width hero at the top of the
 * portrait page is, on the glass, a band down one side of the landscape window. This keeps the
 * physical position and returns the turn a drawing there needs to look exactly like the page
 * did. Null when the two windows do not describe the same display (split screen, a second
 * display), where no physical match exists.
 */
internal fun physicalBox(
    sourceOnScreen: Rect,
    source: ScreenGeometry,
    target: ScreenGeometry,
    corner: Float = 0f,
): HandoffBox? {
    val natural = source.natural
    if (!sameDisplay(natural, target.natural)) return null
    val center = fromNatural(toNatural(sourceOnScreen.center, source.rotation, natural), target.rotation, natural)
    return HandoffBox(
        center = center,
        width = sourceOnScreen.width,
        height = sourceOnScreen.height,
        rotation = handoffTurnDegrees(source.rotation, target.rotation),
        corner = corner,
    )
}

private fun sameDisplay(
    a: Size,
    b: Size,
): Boolean =
    a.width > 0f &&
        a.height > 0f &&
        abs(a.width - b.width) <= a.width * DISPLAY_TOLERANCE &&
        abs(a.height - b.height) <= a.height * DISPLAY_TOLERANCE

/** The share of [rect] that lies on a [screen]-sized display, 0..1. */
internal fun visibleShare(
    rect: Rect,
    screen: Size,
): Float {
    if (rect.width <= 0f || rect.height <= 0f) return 0f
    val visible = rect.intersect(Rect(0f, 0f, screen.width, screen.height))
    if (visible.width <= 0f || visible.height <= 0f) return 0f
    return visible.width * visible.height / (rect.width * rect.height)
}

/** The smallest rectangle, turned by [rotation] degrees about the frame's centre, that still covers [frame]. */
internal fun coverSize(
    rotation: Float,
    frame: Size,
): Size {
    val radians = rotation * PI.toFloat() / 180f
    val c = abs(cos(radians))
    val s = abs(sin(radians))
    return Size(frame.width * c + frame.height * s, frame.width * s + frame.height * c)
}

/**
 * A turn that never shows the window behind the picture.
 *
 * Each step is the mix of [from] and [to], grown where needed to [coverSize] of that step's
 * angle, so at 45° the picture is at its largest and no corner of [frame] is ever uncovered.
 * The centre moves between the two ends' own centres, so the turn starts exactly where the
 * page left off even in a window letterboxed away from a camera cutout.
 */
internal fun coveringTurn(
    progress: Float,
    from: HandoffBox,
    to: HandoffBox,
    frame: Size,
): HandoffBox {
    val rotation = mixFloat(from.rotation, to.rotation, progress)
    val cover = coverSize(rotation, frame)
    return HandoffBox(
        center = Offset(mixFloat(from.center.x, to.center.x, progress), mixFloat(from.center.y, to.center.y, progress)),
        width = max(mixFloat(from.width, to.width, progress), cover.width),
        height = max(mixFloat(from.height, to.height, progress), cover.height),
        rotation = rotation,
    )
}

/** The fitted picture: [aspect] inside [frame], centred; the whole frame when unknown. */
internal fun fittedPicture(
    frame: Size,
    aspect: Float?,
): HandoffBox {
    val ratio = aspect?.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val width = minOf(frame.width, frame.height * ratio)
    val height = minOf(frame.height, frame.width / ratio)
    return HandoffBox(Offset(frame.width / 2f, frame.height / 2f), width, height)
}

// ------------------------------------------------------------------ time

/** Progress through an interval: 0 before [start], 1 from [start] + [duration], eased between. */
internal fun handoffSegment(
    time: Float,
    start: Float,
    duration: Float,
    easing: Easing = Motion.Curve,
): Float {
    if (duration <= 0f) return if (time >= start) 1f else 0f
    return easing.transform(((time - start) / duration).coerceIn(0f, 1f))
}

/** A sine in and out, for the parts of a gesture that should neither snap nor lag. */
internal val HandoffInOut = Easing { fraction -> (1f - cos(PI.toFloat() * fraction)) / 2f }

internal val HandoffLinear: Easing = LinearEasing

/**
 * The step response of a damped spring from 0 to 1, for a settle drawn on a clock.
 *
 * Same parameters as `spring(dampingRatio, stiffness)` with unit mass, so the settle and press
 * springs of [Motion] keep their feel when a transition has to draw them frame by frame.
 */
internal fun handoffSpring(
    millis: Float,
    damping: Float,
    stiffness: Float,
): Float {
    if (millis <= 0f) return 0f
    val omega = sqrt(stiffness)
    val t = millis / 1000f
    return if (damping < 1f) {
        val damped = omega * sqrt(1f - damping * damping)
        1f - exp(-damping * omega * t) * (cos(damped * t) + (damping * omega / damped) * sin(damped * t))
    } else {
        1f - exp(-omega * t) * (1f + omega * t)
    }
}

/**
 * One tide crest passing a point [tau] ms after the wave reached it — the library's cold-start
 * wave (A 20 dp, P 420 ms, D 190 ms), as a fraction of its amplitude.
 */
internal fun tideCrest(tau: Float): Float =
    if (tau <= 0f) {
        0f
    } else {
        -sin(2f * PI.toFloat() * tau / TIDE_PERIOD_MS) * exp(-tau / TIDE_DECAY_MS)
    }

internal const val TIDE_PERIOD_MS = 420f
internal const val TIDE_DECAY_MS = 190f
internal const val TIDE_SPEED_PX_PER_MS_AT_DP = 1.5f
private const val DISPLAY_TOLERANCE = 0.02f

// ------------------------------------------------------------------ choreography

/**
 * One set's clock, in ms from the moment the launch is issued (entering) or the close is asked for
 * (leaving). Both windows read the same numbers, which is what keeps their halves meeting.
 *
 * The player window's own enter animation is [windowDelay] + [windowFade] after its first frame
 * (~[EXPECTED_FIRST_FRAME_MS] on a warm process); the delay is sized so that, on time, the fade
 * ends exactly at [morphStart], by when the page has reached the state the player takes over from
 * ([pageHeld]). A late window only shifts the player's clock, never the page's.
 */
@Immutable
internal data class HandoffTiming(
    val windowDelay: Int,
    val windowFade: Int,
    val pageHeld: Int,
    val morphStart: Int,
    val land: Int,
    val chromeIn: Int,
    val pictureHandoff: Int,
    val exitFinish: Int,
    val exitFade: Int,
    val pageReturn: Int,
)

internal val PlayerTransitionStyle.timing: HandoffTiming
    get() =
        when (this) {
            PlayerTransitionStyle.Turn -> HandoffTiming(20, 100, 260, 260, 900, 880, 360, 640, 160, 660)
            PlayerTransitionStyle.Curtain ->
                HandoffTiming(260, 60, 440, 460, CURTAIN_LAND_LATEST, CURTAIN_LAND_LATEST, 320, 760, 80, 660)
            PlayerTransitionStyle.Glass -> HandoffTiming(100, 80, 240, 320, 980, 940, 560, 700, 100, 520)
            PlayerTransitionStyle.PushIn -> HandoffTiming(160, 80, 340, 380, 1240, 1200, 280, 920, 80, 560)
            PlayerTransitionStyle.Tide -> HandoffTiming(380, 80, 600, 600, 1100, 1060, 280, 440, 80, 900)
            PlayerTransitionStyle.Defocus -> HandoffTiming(160, 80, 320, 380, 1240, 1180, 280, 900, 80, 700)
        }

internal const val EXPECTED_FIRST_FRAME_MS = 140

/**
 * 开幕 opens its gate once the phone has actually been turned to landscape, or after a timeout;
 * the gate itself takes 660 ms. This is the latest the picture can land.
 */
internal const val CURTAIN_GATE_EARLIEST = 700
internal const val CURTAIN_GATE_LATEST = 1200
internal const val CURTAIN_GATE_OPEN = 660
internal const val CURTAIN_LAND_LATEST = CURTAIN_GATE_LATEST + CURTAIN_GATE_OPEN

/**
 * How far the player's clock runs behind the page's, given when its first frame was drawn.
 *
 * On time, the window finishes fading in at [HandoffTiming.morphStart]. A window that arrives
 * later shifts its own clock by the delay, so it takes over from the page's held state instead
 * of from the middle of a turn nobody saw.
 */
internal fun handoffPlayerLag(
    timing: HandoffTiming,
    firstFrameMs: Float,
): Float {
    val visibleAt = firstFrameMs + timing.windowDelay + timing.windowFade
    return (visibleAt - timing.morphStart).coerceAtLeast(0f)
}
