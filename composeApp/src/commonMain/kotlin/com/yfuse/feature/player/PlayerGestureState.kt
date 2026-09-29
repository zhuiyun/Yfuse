package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.yfuse.core.designsystem.DragAxis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 长按快进/快退 — how fast the playhead runs while a press is held down.
 *
 * Holding used to jump to 2× playback, which is a different thing than it looks like:
 * the picture keeps playing and the finger has to stay down to keep it there, so
 * skipping a minute of credits meant holding for thirty seconds and watching them. A
 * held press now runs along the timeline instead, one step of its gear per
 * [HOLD_SEEK_TICK_MS] — 10× to start, 30× once the press has lasted [HOLD_SEEK_RAMP_MS]
 * without the finger shifting gear itself. A sideways slide shifts between standing still,
 * 10×, 30× and 60× ([holdScanGearFor]). This keeps short holds precise while still allowing
 * a long hold to cross an episode.
 *
 * The control proposes a seek every 300ms while held. The player-level latest-wins reducer merges
 * bursts before they reach a local engine or Cast receiver, while the HUD remains immediate.
 */
internal const val HOLD_SEEK_TICK_MS = 300L
internal const val HOLD_SEEK_RAMP_MS = 3_000L

/**
 * What the picture's gestures are in the middle of, and what the HUD says about them: a run of
 * 双击 taps, a held side's scan and its 回到, 长按中间's gear, a drag across the picture, a finger on
 * the progress rail, and 捏合填充's second finger taking over from all of them.
 *
 * These were a dozen locals spread through PlayerControls. Held here and remembered once, they move
 * only through the methods below, which the pointer detectors and effects call, and PlayerControls
 * reads the holder instead. What changes on every sample — the HUD's text, where a swipe or a held
 * side has got to — is read only where it is drawn: [PlayerGestureHud] and [PictureScrubPreview].
 *
 * Snapshot state where something composes from a value, plain fields where nothing does. All of it
 * is touched on the main thread: the pointer detectors, the hold's ticking loop and the effects.
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
     * Returns where to seek: [positionMs] moved by these taps, inside the item.
     */
    fun burstSeek(
        direction: Int,
        at: Offset,
        taps: Int,
        stepMs: Long,
        positionMs: Long,
        durationMs: Long,
    ): Long {
        val moved = burst.add(direction, stepMs, taps)
        pulsePosition = at
        pulseRevision++
        hud = "${if (direction < 0) "快退" else "快进"} ${burst.totalMs / 1_000L} 秒"
        return (positionMs + direction * moved).coerceIn(0L, durationMs)
    }

    // -------------------------------------------------------- 长按扫描

    /** -1 while a held side rewinds, +1 while it fast-forwards, 0 when no side is held. */
    var scanDirection by mutableIntStateOf(0)
        private set

    /** The newest position the held side has proposed to the playback coordinator. */
    var scanTargetMs by mutableLongStateOf(0L)
        private set

    /**
     * For a few seconds after a scan lets go, where it set out from — the 回到 offer: a hold that ran
     * further than meant costs one tap, not a hunt along the rail. Null otherwise.
     */
    var scanUndoMs: Long? by mutableStateOf(null)
        private set

    val scanning: Boolean
        get() = scanDirection != 0

    // Where the current scan set out from.
    private var scanOriginMs = 0L

    // 长按扫描换挡: the held side's gear, followed by the pointer observer and the ticking loop.
    private val gears = HoldScanGears()

    /** A held side took hold going [direction], with the finger at [x] and the playhead at [positionMs]. */
    fun startScan(
        direction: Int,
        x: Float,
        positionMs: Long,
    ) {
        scanTargetMs = positionMs
        scanOriginMs = positionMs
        scanUndoMs = null
        gears.start(x)
        scanDirection = direction
    }

    /**
     * Runs a side held going [direction] until cancelled — PlayerControls keys it on [scanDirection],
     * so the release stops it: a step of the gear every [HOLD_SEEK_TICK_MS], each proposed through
     * [onSeek]. Re-stamping the HUD every tick also keeps its auto-clear from taking it away mid-hold.
     * [onShift] is told when the ramp changes gear on its own.
     */
    suspend fun runScan(
        direction: Int,
        stepPx: Float,
        durationMs: () -> Long,
        onShift: () -> Unit,
        onSeek: (Long) -> Unit,
    ) {
        if (direction == 0) return
        var heldMs = 0L
        while (currentCoroutineContext().isActive) {
            val span = durationMs().coerceAtLeast(1L)
            // A finger that has not slid gets the ramp holds always had: three seconds at 10×, then 30×.
            if (heldMs >= HOLD_SEEK_RAMP_MS && gears.ramp(direction, stepPx)) onShift()
            val step = holdScanStepMs(gears.gear, HOLD_SEEK_TICK_MS)
            // Standing still proposes nothing new: the last seek stands, and letting go lands there.
            if (step > 0L) {
                scanTargetMs = (scanTargetMs + direction * step).coerceIn(0L, span)
                // Proposed seek while held; PlayerRoot merges closely-spaced commands latest-wins.
                onSeek(scanTargetMs)
            }
            hud = holdScanLabel(direction, gears.gear, scanTargetMs, span)
            delay(HOLD_SEEK_TICK_MS)
            heldMs += HOLD_SEEK_TICK_MS
        }
    }

    /**
     * The finger holding a side is at [x]. A slide shifts gear, and the HUD says so now rather than
     * on the next tick, which may be 300 ms off. True when it shifted.
     */
    fun followScan(
        x: Float,
        stepPx: Float,
        durationMs: Long,
    ): Boolean {
        val direction = scanDirection
        if (!gears.follow(x, direction, stepPx)) return false
        hud = holdScanLabel(direction, gears.gear, scanTargetMs, durationMs.coerceAtLeast(1L))
        return true
    }

    /**
     * The held side let go. The engine already follows the ticks, so this only stops them, offering
     * 回到 when the scan moved at all. True when a side was held.
     */
    fun releaseScan(): Boolean {
        if (scanDirection == 0) return false
        scanDirection = 0
        scanUndoMs = scanOriginMs.takeIf { it != scanTargetMs }
        return true
    }

    /** The 回到 offer ran out; the scan stands. */
    fun expireScanUndo() {
        scanUndoMs = null
    }

    /** 回到 was tapped: where to seek back to, said on the HUD — or null once the offer has gone. */
    fun takeScanUndo(): Long? {
        val origin = scanUndoMs ?: return null
        scanUndoMs = null
        hud = "已回到 ${origin.asClock()}"
        return origin
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
    var dragAxis = DragAxis.Undecided
        private set

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
        dragAxis = DragAxis.Undecided
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
        // A finger that drifts while held is still holding, not scrubbing: the hold owns the
        // timeline until it lets go, and a slide during either hold changes gear instead.
        if (scanning || boosting) return null
        dragTotalX += dx
        dragTotalY += dy
        dragAxis = lockedPlayerDragAxis(dragAxis, dragTotalX, dragTotalY)
        return when (dragAxis) {
            DragAxis.Horizontal -> {
                // Brightness/volume drags stay available to guests; only the
                // horizontal scrub is the host's to make.
                if (watchGuest) {
                    hud = "房主控制播放"
                    return null
                }
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
     * for an upright drag, under a held side, with nothing to seek in, or for a guest of the room.
     */
    fun endDrag(
        durationMs: Long,
        watchGuest: Boolean,
    ): Long? {
        pictureScrubMs = null
        return dragSeekMs.takeIf { !scanning && dragAxis == DragAxis.Horizontal && durationMs > 0 && !watchGuest }
    }

    /** The drag was taken away rather than let go: nothing lands, and the HUD clears. */
    fun cancelDrag() {
        hud = null
        pictureScrubMs = null
    }

    /** The frame the picture's gestures have got to, for [PictureScrubPreview]: a held side's, else a swipe's. */
    val previewMs: Long?
        get() = if (scanDirection != 0) scanTargetMs else pictureScrubMs

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
     * A second finger takes over: a held side stops where it got to, without 回到, a swipe's preview
     * goes with the drag it belonged to, and the HUD clears. True when that let go of 长按中间, and
     * the caller puts the rate back.
     */
    fun secondFinger(): Boolean {
        scanDirection = 0
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
