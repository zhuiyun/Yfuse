package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.yfuse.core.designsystem.DragAxis
import kotlin.math.abs

/**
 * Which way a drag across the picture goes: sideways to seek, up and down for brightness or volume.
 * Decided once, by the way the finger went to get past touch slop, and kept until it lets go
 * ([lockedPlayerDragAxis]). Re-decided from the running totals on every sample, a brightness or
 * volume drag that came back down with a little sideways drift turned into a seek on release, and a
 * scrub that went there and back into brightness or volume.
 */
internal class PictureDragAxis {
    /** The axis the drag is held to; [DragAxis.Undecided] until it has gone anywhere. */
    var axis = DragAxis.Undecided
        private set

    /** True for a seek, false for brightness or volume; null until the drag has gone anywhere. */
    val sideways: Boolean?
        get() =
            when (axis) {
                DragAxis.Horizontal -> true
                DragAxis.Vertical -> false
                DragAxis.Undecided -> null
            }

    fun reset() {
        axis = DragAxis.Undecided
    }

    /** The finger is [dx], [dy] from where it went down; the axis the drag is held to. */
    fun follow(
        dx: Float,
        dy: Float,
    ): Boolean {
        axis = lockedPlayerDragAxis(axis, dx, dy)
        return axis == DragAxis.Horizontal
    }
}

/**
 * What the picture's gestures are in the middle of, and what the HUD says about them: a run of
 * 双击 taps, 长按中间's gear, a drag across the picture, a finger on the progress rail, and
 * 捏合填充's second finger taking over from all of them.
 *
 * These were a dozen locals spread through PlayerControls. Held here and remembered once, they move
 * only through the methods below, which the pointer detectors and effects call, and PlayerControls
 * reads the holder instead. What changes on every sample — the HUD's text, where a swipe has got
 * to — is read only where it is drawn: [PlayerGestureHud] and [PictureScrubPreview].
 *
 * Snapshot state where something composes from a value, plain fields where nothing does. All of it
 * is touched on the main thread: the pointer detectors and the effects.
 */
@Stable
internal class PlayerGestureState(
    private val burst: DoubleTapSeekBurst = DoubleTapSeekBurst(),
) {
    /** What the gesture HUD says — 快进 30 秒, 音量 40%, where a scrub will land — or null when it is clear. */
    var hud: String? by mutableStateOf(null)
        private set

    /** Puts [message] on the HUD; null clears it. */
    fun say(message: String?) {
        hud = message
    }

    // -------------------------------------------------------- 双击快进快退

    /** Bumped by every burst step for [SeekBurstFeedback]'s pulse; PlayerControls counts it from each item's start. */
    var pulseRevision by mutableIntStateOf(0)
        private set

    /** Where the latest step's taps landed, for its pulse. */
    var pulsePosition by mutableStateOf(Offset.Zero)
        private set

    /** Whether a tap going [direction] now adds to the running burst. */
    fun burstContinues(direction: Int): Boolean = burst.continues(direction)

    /**
     * [taps] more taps going [direction], at [at], each worth [stepMs]. Taps in quick succession on
     * the same side add up, and the HUD reports the running total rather than "10 秒" each time.
     * Returns where to seek: [positionMs] moved by these taps, inside the item. Null while the
     * duration is unknown ([doubleTapSeekTarget]): then nothing is counted and nothing is said.
     */
    fun burstSeek(
        direction: Int,
        at: Offset,
        taps: Int,
        stepMs: Long,
        positionMs: Long,
        durationMs: Long,
    ): Long? {
        if (doubleTapSeekTarget(positionMs, durationMs, 0L) == null) return null
        val moved = burst.add(direction, stepMs, taps)
        pulsePosition = at
        pulseRevision++
        hud = "${if (direction < 0) "快退" else "快进"} ${burst.totalMs / 1_000L} 秒"
        return doubleTapSeekTarget(positionMs, durationMs, direction * moved)
    }

    // -------------------------------------------------------- 长按中间

    /** The gear while the middle third is held, null otherwise. */
    var boostGear: Int? by mutableStateOf(null)
        private set

    val boosting: Boolean
        get() = boostGear != null

    // Where the finger was when the hold took; the gears are counted from there.
    private var boostOriginX = 0f

    /** The middle took hold with the finger at [x]: 2× to start, and the HUD makes way. Returns the rate. */
    fun startBoost(x: Float): Float {
        boostOriginX = x
        boostGear = SPEED_BOOST_DEFAULT_GEAR
        hud = null
        return SPEED_BOOST_GEARS[SPEED_BOOST_DEFAULT_GEAR]
    }

    /** The finger holding the middle is at [x]: the new rate when a slide shifted gear, null otherwise. */
    fun followBoost(
        x: Float,
        stepPx: Float,
    ): Float? {
        val gear = boostGear ?: return null
        val next = speedBoostGearFor(dragX = x - boostOriginX, stepPx = stepPx, current = gear)
        if (next == gear) return null
        boostGear = next
        return SPEED_BOOST_GEARS[next]
    }

    /** Lets go of 长按中间. True when it was held, and the caller puts the rate back. */
    fun endBoost(): Boolean {
        if (boostGear == null) return false
        boostGear = null
        return true
    }

    // -------------------------------------------------------- 横滑定位 · 竖滑亮度与音量

    /** Where a sideways swipe across the picture would land, for the card over the HUD; null otherwise. */
    var pictureScrubMs: Long? by mutableStateOf(null)
        private set

    /**
     * Decided on the first move and kept until the finger lifts: only a drag that began sideways
     * ever seeks, however far a volume drag's thumb wanders. A plain field: only the release reads it.
     */
    private val axis = PictureDragAxis()

    val dragAxis: DragAxis
        get() = axis.axis

    private var dragStartX = 0f
    private var dragTotalX = 0f
    private var dragTotalY = 0f
    private var dragSeekMs = 0L
    private val swipePace = SwipeSeekPace()
    private var volumeAtDragStart = 0f
    private var brightnessAtDragStart = 0f

    /** A drag across the picture set out from [x], with the playhead, volume and brightness where they are. */
    fun startDrag(
        x: Float,
        positionMs: Long,
        volume: Float,
        brightness: Float,
    ) {
        dragStartX = x
        dragTotalX = 0f
        dragTotalY = 0f
        axis.reset()
        pictureScrubMs = null
        dragSeekMs = positionMs
        swipePace.reset()
        volumeAtDragStart = volume
        brightnessAtDragStart = brightness
    }

    /**
     * One move of the drag: [dx], [dy] since the last, [dtMs] after it, on a picture [width] × [height]
     * px. Sideways moves where the swipe would land; upright answers with the brightness or volume
     * to set, [swapBrightnessVolume] deciding which half is which. Either says so on the HUD.
     */
    fun drag(
        dx: Float,
        dy: Float,
        dtMs: Long,
        width: Int,
        height: Int,
        density: Float,
        positionMs: Long,
        durationMs: Long,
        watchGuest: Boolean,
        swapBrightnessVolume: Boolean,
    ): PictureLevel? {
        // A finger that drifts during 长按中间 is still holding, not scrubbing: a slide then
        // changes gear instead.
        if (boosting) return null
        dragTotalX += dx
        dragTotalY += dy
        axis.follow(dragTotalX, dragTotalY)
        return when (dragAxis) {
            DragAxis.Horizontal -> {
                // Brightness/volume drags stay available to guests; only the
                // horizontal scrub is the host's to make.
                if (watchGuest) {
                    hud = "房主控制播放"
                    return null
                }
                // Nothing to scrub along before the duration is known, and the release
                // would not seek: no target to show either.
                if (durationMs <= 0L) return null
                val span = durationMs.coerceAtLeast(1L)
                val step = swipePace.step(dx, dtMs, width, density, span)
                dragSeekMs = (dragSeekMs + step).coerceIn(0L, span)
                val delta = dragSeekMs - positionMs
                val sign = if (delta < 0L) "-" else "+"
                hud = "$sign${abs(delta).asClock()} · ${dragSeekMs.asClock()} / ${span.asClock()}"
                pictureScrubMs = dragSeekMs
                null
            }
            DragAxis.Vertical -> {
                pictureScrubMs = null
                val delta = -dragTotalY / height
                // 亮度与音量左右互换 flips which half answers with which.
                if ((dragStartX < width / 2f) != swapBrightnessVolume) {
                    val target = (brightnessAtDragStart + delta).coerceIn(0.02f, 1f)
                    hud = "亮度 ${(target * 100).toInt()}%"
                    PictureLevel.Brightness(target)
                } else {
                    val target = (volumeAtDragStart + delta).coerceIn(0f, 1f)
                    hud = "音量 ${(target * 100).toInt()}%"
                    PictureLevel.Volume(target)
                }
            }
            DragAxis.Undecided -> null
        }
    }

    /**
     * The drag let go, taking its preview with it. Returns where a sideways swipe lands, or null: not
     * for an upright drag, with nothing to seek in, or for a guest of the room.
     */
    fun endDrag(
        durationMs: Long,
        watchGuest: Boolean,
    ): Long? {
        pictureScrubMs = null
        return dragSeekMs.takeIf { dragAxis == DragAxis.Horizontal && durationMs > 0 && !watchGuest }
    }

    /** The drag was taken away rather than let go: nothing lands, and the HUD clears. */
    fun cancelDrag() {
        hud = null
        pictureScrubMs = null
    }

    /** The frame a swipe across the picture has got to, for [PictureScrubPreview]. */
    val previewMs: Long?
        get() = pictureScrubMs

    // -------------------------------------------------------- 进度条

    /**
     * A finger on the progress rail. Held still over a preview it sends no samples, and the timer
     * used to hide the bar out from under it — cancelling the drag it was about to commit.
     */
    var scrubbing by mutableStateOf(false)
        private set

    /** 精细定位 is taught once the rail has been dragged, while the controls are still up to read it. */
    var fineScrubArmed by mutableStateOf(false)
        private set

    /** A sample from the rail. True for a drag's first, the one interaction a whole drag counts as. */
    fun scrub(): Boolean {
        fineScrubArmed = true
        if (scrubbing) return false
        scrubbing = true
        return true
    }

    /** The rail let go — or was taken away mid-drag, which reports no end of its own. */
    fun endScrub() {
        scrubbing = false
    }

    // -------------------------------------------------------- 捏合填充

    /**
     * A second finger takes over: 长按中间 lets go, a swipe's preview goes with the drag it belonged
     * to, and the HUD clears. True when that let go of 长按中间, and the caller puts the rate back.
     */
    fun secondFinger(): Boolean {
        val boostEnded = endBoost()
        pictureScrubMs = null
        hud = null
        return boostEnded
    }
}

/** What an upright drag on the picture sets; see [PlayerGestureState.drag]. */
internal sealed interface PictureLevel {
    val level: Float

    data class Brightness(
        override val level: Float,
    ) : PictureLevel

    data class Volume(
        override val level: Float,
    ) : PictureLevel
}

/** The picture's gestures for the life of the controls: remembered once, never keyed. */
@Composable
internal fun rememberPlayerGestureState(): PlayerGestureState = remember { PlayerGestureState() }
