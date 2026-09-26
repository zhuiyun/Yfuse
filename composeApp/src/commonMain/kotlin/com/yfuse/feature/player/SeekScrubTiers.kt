package com.yfuse.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.HapticSignal

/**
 * 拖动 2.0 on the progress bar: the further the finger moves up off the rail, the finer the drag.
 * iOS slows a scrubber the same way, and YouTube's precise seeking pulls up a filmstrip.
 */
internal enum class SeekScrubTier {
    /** Within [SeekFineLift]: the thumb is where the finger is. */
    Normal,

    /** [SeekFineLift] to [SeekFilmstripLift]: a quarter of the finger's movement. */
    Fine,

    /** Past [SeekFilmstripLift], with trickplay: one storyboard frame per [SeekFilmstripStep]. */
    Filmstrip,
}

/** How far up the finger goes before the drag turns fine. */
internal val SeekFineLift: Dp = 34.dp

/** How far up it goes before the filmstrip takes over. */
internal val SeekFilmstripLift: Dp = 84.dp

/**
 * How far back past a tier's line the finger has to come before the drag coarsens again, so a
 * finger resting on the line does not rattle between two speeds.
 */
internal val SeekTierHysteresis: Dp = 6.dp

/** Sideways travel per filmstrip frame. */
internal val SeekFilmstripStep: Dp = 28.dp

/** The fine tier's speed: a quarter of the finger's. */
internal const val SEEK_FINE_SPEED = 0.25f

/** How many frames the filmstrip shows at most; the selected one sits in the middle. */
internal const val SEEK_FILMSTRIP_FRAMES = 7

/** What the fine tier calls itself beside the scrubbed time. */
internal const val SEEK_FINE_LABEL = "¼ 速"

/**
 * The tier for a finger [liftPx] above where it went down, given the tier it is in now. Moving up
 * enters a tier at its line; coming back down leaves it only [hysteresisPx] below that line.
 * Without a [filmstrip] the drag stops at fine.
 */
internal fun seekScrubTierFor(
    liftPx: Float,
    current: SeekScrubTier,
    fineLiftPx: Float,
    filmstripLiftPx: Float,
    hysteresisPx: Float,
    filmstrip: Boolean,
): SeekScrubTier {
    if (!liftPx.isFinite()) return if (current == SeekScrubTier.Filmstrip && !filmstrip) SeekScrubTier.Fine else current

    fun reached(
        tier: SeekScrubTier,
        linePx: Float,
    ): Boolean = liftPx >= linePx || (current >= tier && liftPx >= linePx - hysteresisPx)

    return when {
        filmstrip && reached(SeekScrubTier.Filmstrip, filmstripLiftPx) -> SeekScrubTier.Filmstrip
        reached(SeekScrubTier.Fine, fineLiftPx) -> SeekScrubTier.Fine
        else -> SeekScrubTier.Normal
    }
}

/** A finer tier crosses a line ([HapticSignal.Threshold]); a coarser one falls back over it. */
internal fun seekScrubTierHaptic(
    from: SeekScrubTier,
    to: SeekScrubTier,
): HapticSignal? =
    when {
        to > from -> HapticSignal.Threshold
        to < from -> HapticSignal.ThresholdRelease
        else -> null
    }

/**
 * A storyboard's frames as positions on a [durationMs]-long file: how many there are, where each
 * was taken, and which one a position shows.
 */
internal class SeekFilmstripFrames(
    private val storyboard: TrickplayStoryboard,
    private val durationMs: Long,
) {
    private val intervalMs = storyboard.intervalMs.coerceAtLeast(1L)

    /** Frames taken before the end of the file — a sprite sheet is often padded past it. */
    val count: Int =
        if (storyboard.frames.isNotEmpty()) {
            storyboard.frames.count { it.positionMs < durationMs.coerceAtLeast(1L) }.coerceAtLeast(1)
        } else {
            val byDuration = ((durationMs.coerceAtLeast(1L) - 1L) / intervalMs + 1L).toInt()
            if (storyboard.thumbnailCount > 0) minOf(storyboard.thumbnailCount, byDuration) else byDuration
        }

    /** The regular spacing between frames, or null where the storyboard lists its own positions. */
    val stepMs: Long? get() = intervalMs.takeIf { storyboard.frames.isEmpty() }

    /** Where frame [index] was taken. */
    fun startMs(index: Int): Long {
        val clamped = index.coerceIn(0, count - 1)
        val position =
            if (storyboard.frames.isNotEmpty()) storyboard.frames[clamped].positionMs else clamped * intervalMs
        return position.coerceIn(0L, durationMs.coerceAtLeast(0L))
    }

    /** Frame [index] as a fraction of the file. */
    fun fraction(index: Int): Float =
        if (durationMs <= 0L) 0f else (startMs(index).toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

    /** The frame [positionMs] shows: the last one taken at or before it. */
    fun indexAt(positionMs: Long): Int {
        var index = 0
        if (storyboard.frames.isNotEmpty()) {
            for (candidate in 0 until count) {
                if (storyboard.frames[candidate].positionMs > positionMs) break
                index = candidate
            }
        } else {
            index = (positionMs.coerceAtLeast(0L) / intervalMs).toInt()
        }
        return index.coerceIn(0, count - 1)
    }

    /** The frame taken nearest [fraction] of the way through. */
    fun nearestIndex(fraction: Float): Int {
        val positionMs = (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong()
        val at = indexAt(positionMs)
        val next = (at + 1).coerceAtMost(count - 1)
        return if (next != at && startMs(next) - positionMs < positionMs - startMs(at)) next else at
    }
}

/**
 * Where Shift + an arrow key goes from [positionMs]: the next frame's start going forward; going
 * back, the start of the frame on screen, or the one before it when the playhead has barely left
 * that start — so a held key walks back frame by frame instead of landing on the same one.
 */
internal fun filmstripStepTargetMs(
    frames: SeekFilmstripFrames,
    positionMs: Long,
    direction: Int,
): Long {
    val at = frames.indexAt(positionMs)
    return when {
        direction > 0 -> frames.startMs(at + 1)
        direction < 0 && positionMs - frames.startMs(at) > FILMSTRIP_STEP_BACK_SLACK_MS -> frames.startMs(at)
        direction < 0 -> frames.startMs(at - 1)
        else -> positionMs
    }
}

/** How far past a frame's start still counts as standing on it, for stepping back. */
private const val FILMSTRIP_STEP_BACK_SLACK_MS = 1_000L

/**
 * How many frames fit a strip [availablePx] wide: frames [framePx] wide, [gapPx] apart, inside
 * [paddingPx] either side. Always odd, so the selected frame has the middle; at most
 * [SEEK_FILMSTRIP_FRAMES], at least the selected one alone.
 */
internal fun filmstripSlots(
    availablePx: Float,
    framePx: Float,
    gapPx: Float,
    paddingPx: Float,
): Int {
    if (framePx + gapPx <= 0f) return 1
    val fit = ((availablePx - 2f * paddingPx + gapPx) / (framePx + gapPx)).toInt()
    val odd = if (fit % 2 == 0) fit - 1 else fit
    return odd.coerceIn(1, SEEK_FILMSTRIP_FRAMES)
}

/** "胶片条 · 每格 10 秒", or "胶片条 · 逐格" where the frames keep no regular spacing. */
internal fun filmstripHeading(stepMs: Long?): String {
    val tenths = stepMs?.takeIf { it >= 100L }?.div(100L) ?: return "胶片条 · 逐格"
    val seconds = if (tenths % 10L == 0L) "${tenths / 10L}" else "${tenths / 10L}.${tenths % 10L}"
    return "胶片条 · 每格 $seconds 秒"
}

/**
 * One drag on the progress bar, turned into fractions of it tier by tier. Plain state, no
 * Compose: the bar feeds it pointer positions and draws what comes back.
 *
 * Every change of tier picks up from where the thumb is drawn, so the thumb never jumps as the
 * precision changes:
 * - [SeekScrubTier.Normal] starts with the thumb under the finger. After a finer tier, the rest of
 *   the rail is spread over the rest of the finger's way to each end, so the thumb meets the
 *   finger again at the ends instead of snapping back under it.
 * - [SeekScrubTier.Fine] moves a quarter of the finger's sideways travel.
 * - [SeekScrubTier.Filmstrip] steps one frame per [frameStepPx]; each step re-anchors, so coming
 *   back a frame takes a full step and a resting finger never rattles.
 */
internal class SeekScrubGesture(
    private val fineLiftPx: Float,
    private val filmstripLiftPx: Float,
    private val hysteresisPx: Float,
    private val frameStepPx: Float,
) {
    var tier: SeekScrubTier = SeekScrubTier.Normal
        private set

    /** The filmstrip frame under the finger, while in [SeekScrubTier.Filmstrip]. */
    var frame: Int = 0
        private set

    /** How far above where it went down the finger is now, never below zero. */
    var liftPx: Float = 0f
        private set

    private var originY = 0f
    private var anchorX = 0f
    private var anchorFraction = 0f

    /** The drag started: down at [downY], sideways past slop at [x]. Returns the fraction under it. */
    fun begin(
        x: Float,
        downY: Float,
        width: Float,
    ): Float {
        tier = SeekScrubTier.Normal
        frame = 0
        liftPx = 0f
        originY = downY
        anchorX = x
        anchorFraction = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return anchorFraction
    }

    /**
     * The finger moved to ([x], [y]) on a [width]-wide rail. Returns the fraction it now points at,
     * before any magnet. [shown] is where the thumb is drawn; [frames] is null without trickplay.
     */
    fun move(
        x: Float,
        y: Float,
        width: Float,
        shown: Float,
        frames: SeekFilmstripFrames?,
    ): Float {
        val span = width.coerceAtLeast(1f)
        liftPx = (originY - y).coerceAtLeast(0f)
        val filmstrip = frames != null && frames.count > 1
        val next = seekScrubTierFor(liftPx, tier, fineLiftPx, filmstripLiftPx, hysteresisPx, filmstrip)
        if (next != tier) {
            tier = next
            anchorX = x
            anchorFraction = shown.coerceIn(0f, 1f)
            if (next == SeekScrubTier.Filmstrip && frames != null) frame = frames.nearestIndex(anchorFraction)
        }
        return when (tier) {
            SeekScrubTier.Normal -> convergingFraction(x, span, anchorX, anchorFraction)
            SeekScrubTier.Fine -> (anchorFraction + (x - anchorX) / span * SEEK_FINE_SPEED).coerceIn(0f, 1f)
            SeekScrubTier.Filmstrip -> {
                // Only ever reached with frames; this keeps a pointer handler from throwing if not.
                val strip = frames ?: return anchorFraction
                val steps = ((x - anchorX) / frameStepPx).toInt()
                if (steps != 0) {
                    frame = (frame + steps).coerceIn(0, strip.count - 1)
                    anchorX += steps * frameStepPx
                }
                strip.fraction(frame)
            }
        }
    }
}

/**
 * The fraction for a finger at [x] on a [width]-wide rail, when [anchorX] was at [anchorFraction]:
 * the stretch of rail on each side of the anchor is laid over the finger's remaining way to that
 * end. Where the anchor is the finger's own fraction this is simply `x / width`.
 */
internal fun convergingFraction(
    x: Float,
    width: Float,
    anchorX: Float,
    anchorFraction: Float,
): Float {
    val finger = x.coerceIn(0f, width)
    val anchor = anchorX.coerceIn(0f, width)
    val from = anchorFraction.coerceIn(0f, 1f)
    val fraction =
        when {
            finger >= anchor && width - anchor >= 1f -> from + (finger - anchor) / (width - anchor) * (1f - from)
            finger < anchor && anchor >= 1f -> from - (anchor - finger) / anchor * from
            else -> from
        }
    return fraction.coerceIn(0f, 1f)
}
