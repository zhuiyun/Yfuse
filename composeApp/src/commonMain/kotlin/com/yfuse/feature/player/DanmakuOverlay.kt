package com.yfuse.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuKind
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.sc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlin.math.max
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Laid-out lines kept between windows. Past it, all but the current window's own are let go; a
 * layout holds far more than the width this cache used to keep.
 */
private const val MAX_CACHED_DANMAKU_LAYOUTS = 512

/**
 * The most comments one lane carries at a time. Short lines clear the screen's edge so quickly
 * that a busy moment could otherwise stack dozens in a single lane, every one drawn every frame.
 * At the standard size a lane packed with four-character lines stays under it on a phone.
 */
private const val MAX_DANMAKU_PER_LANE = 16
private const val FIXED_DURATION_MS = 4_000L
private const val POSITION_RESET_THRESHOLD_MS = 1_000L
private const val WINDOW_BUCKET_MS = 1_000L

/** A lane cache entry for a comment that found no room when it arrived, and stays off screen. */
private const val DROPPED_LANE = -1

/** No lane decided yet, in [allocateDanmakuLanes]' working arrays. */
private const val UNDECIDED_LANE = -2

/** No input, in [allocateDanmakuLanes]' per-lane look-ahead. */
private const val NO_INPUT = -1

/**
 * Separates a runtime pipeline restart from a user seek.
 *
 * A YCore recovery can reopen the source a few seconds behind the last rendered position. The
 * player must catch those media samples up, but the danmaku consumer has already displayed that
 * interval and must not rewind and replay it. User-initiated backward seeks do not increment this
 * fence, so they keep the normal timeline-reset behaviour.
 */
internal object DanmakuRuntimeRecoveryFence {
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun markRecovery() {
        mutableRevision.update { current -> current + 1L }
    }
}

/**
 * Which comment a lane, or a finger's hold, belongs to. A block, 合并重复 or a refetch rebuilds the
 * list and moves every index, but a line keeps its time, text, colour and kind; [ordinal] tells
 * apart lines identical in all four, which 合并重复 switched off can leave side by side.
 */
internal data class DanmakuKey(
    val timeMs: Long,
    val text: String,
    val color: Long,
    val kind: DanmakuKind,
    val ordinal: Int = 0,
)

internal fun DanmakuComment.danmakuKey(ordinal: Int = 0): DanmakuKey = DanmakuKey(timeMs, text, color, kind, ordinal)

/**
 * The keys of the time-sorted [comments]' entries [from] until [until], in one pass however many
 * share a moment. Identical lines are counted from the first comment at their moment, wherever the
 * range starts, so each ordinal is the one [containsDanmaku] counts to.
 */
internal fun danmakuKeysIn(
    comments: List<DanmakuComment>,
    from: Int,
    until: Int,
): List<DanmakuKey> {
    if (from >= until) return emptyList()
    var index = from
    while (index > 0 && comments[index - 1].timeMs == comments[from].timeMs) index--
    val keys = ArrayList<DanmakuKey>(until - from)
    val counted = HashMap<DanmakuKey, Int>()
    while (index < until) {
        val first = comments[index].danmakuKey()
        val ordinal = counted[first] ?: 0
        counted[first] = ordinal + 1
        if (index >= from) keys += if (ordinal == 0) first else first.copy(ordinal = ordinal)
        index++
    }
    return keys
}

/** Whether the time-sorted list holds the comment [key] names. */
internal fun List<DanmakuComment>.containsDanmaku(key: DanmakuKey): Boolean {
    var index = lowerBoundDanmaku(this, key.timeMs)
    var ordinal = 0
    while (index < size && this[index].timeMs == key.timeMs) {
        val comment = this[index]
        if (comment.text == key.text && comment.color == key.color && comment.kind == key.kind) {
            if (ordinal == key.ordinal) return true
            ordinal++
        }
        index++
    }
    return false
}

internal data class DanmakuLayoutInput(
    val index: Int,
    val comment: DanmakuComment,
    /** Measured text width in the same units as [allocateDanmakuLanes]' viewport. */
    val width: Float,
    /** Who [comment] is from one list to the next; the overlay passes [danmakuKeysIn]'s. */
    val key: DanmakuKey = comment.danmakuKey(),
)

internal data class DanmakuLanePlacement(
    val input: DanmakuLayoutInput,
    val lane: Int,
)

private data class LaneTail(
    val startedAtMs: Long,
    val width: Float,
    val kind: DanmakuKind,
    val durationMs: Long,
)

/**
 * Assigns each entering comment once. If every physical lane is occupied at its timestamp,
 * the comment is dropped at admission rather than making an already-flying comment disappear.
 *
 * [laneCache] carries those decisions from one window to the next by [DanmakuKey], so a comment
 * keeps its lane, or stays dropped, however the list around it changes. A comment admitted among
 * ones that already have lanes (a block undone, 合并重复 switched off, a line just sent) has to
 * clear the one that follows it in a lane as well as the one before.
 *
 * A lane already carrying [maxPerLane] comments at a comment's moment is occupied as well. The cap
 * is on admission: a comment let in among placed ones is not held to the moments after its own.
 */
internal fun allocateDanmakuLanes(
    inputs: List<DanmakuLayoutInput>,
    laneCount: Int,
    viewportWidth: Float,
    scrollDurationMs: Long,
    fixedDurationMs: Long = FIXED_DURATION_MS,
    laneCache: MutableMap<DanmakuKey, Int>? = null,
    maxPerLane: Int = Int.MAX_VALUE,
): List<DanmakuLanePlacement> {
    if (laneCount <= 0 || viewportWidth <= 0f) return emptyList()
    val tails = arrayOfNulls<LaneTail>(laneCount)
    val cached =
        IntArray(inputs.size) { position ->
            laneCache?.get(inputs[position].key)?.takeIf { it < laneCount } ?: UNDECIDED_LANE
        }
    // For each lane, the next input already placed in it; walked forward as the inputs are.
    val nextInLane = IntArray(inputs.size) { NO_INPUT }
    val upcoming = IntArray(laneCount) { NO_INPUT }
    for (position in inputs.indices.reversed()) {
        val lane = cached[position]
        if (lane >= 0) {
            nextInLane[position] = upcoming[lane]
            upcoming[lane] = position
        }
    }

    // When each lane's comments leave the screen, in the order they entered it: those gone by a
    // comment's moment are stepped past from the front, and the rest are what [maxPerLane] counts.
    val leaving = Array(laneCount) { ArrayList<Long>() }
    val gone = IntArray(laneCount)

    fun crowded(
        lane: Int,
        atMs: Long,
    ): Boolean {
        val ends = leaving[lane]
        while (gone[lane] < ends.size && ends[gone[lane]] <= atMs) gone[lane]++
        return ends.size - gone[lane] >= maxPerLane
    }

    fun tailOf(input: DanmakuLayoutInput) =
        LaneTail(
            startedAtMs = input.comment.timeMs,
            width = input.width,
            kind = input.comment.kind,
            durationMs = if (input.comment.kind == DanmakuKind.Scroll) scrollDurationMs else fixedDurationMs,
        )
    return buildList {
        inputs.forEachIndexed { position, input ->
            val cachedLane = cached[position]
            if (cachedLane >= 0) {
                val tail = tailOf(input)
                upcoming[cachedLane] = nextInLane[position]
                tails[cachedLane] = tail
                leaving[cachedLane].add(tail.startedAtMs + tail.durationMs)
                add(DanmakuLanePlacement(input, cachedLane))
                return@forEachIndexed
            }
            if (cachedLane == DROPPED_LANE) return@forEachIndexed
            val laneOrder: IntProgression =
                if (input.comment.kind == DanmakuKind.Bottom) {
                    (laneCount - 1) downTo 0
                } else {
                    0 until laneCount
                }
            val tail = tailOf(input)
            val lane =
                laneOrder.firstOrNull { candidate ->
                    val following = upcoming[candidate]
                    !crowded(candidate, input.comment.timeMs) &&
                        canEnterLane(
                            previous = tails[candidate],
                            next = input,
                            viewportWidth = viewportWidth,
                            scrollDurationMs = scrollDurationMs,
                        ) &&
                        (
                            following == NO_INPUT ||
                                canEnterLane(
                                    previous = tail,
                                    next = inputs[following],
                                    viewportWidth = viewportWidth,
                                    scrollDurationMs = scrollDurationMs,
                                )
                        )
                }
            if (lane == null) {
                laneCache?.put(input.key, DROPPED_LANE)
                return@forEachIndexed
            }
            laneCache?.put(input.key, lane)
            tails[lane] = tail
            leaving[lane].add(tail.startedAtMs + tail.durationMs)
            add(DanmakuLanePlacement(input, lane))
        }
    }
}

private fun canEnterLane(
    previous: LaneTail?,
    next: DanmakuLayoutInput,
    viewportWidth: Float,
    scrollDurationMs: Long,
): Boolean {
    previous ?: return true
    val gapMs = next.comment.timeMs - previous.startedAtMs
    if (gapMs < 0L) return false
    if (previous.kind != DanmakuKind.Scroll || next.comment.kind != DanmakuKind.Scroll) {
        return gapMs >= previous.durationMs
    }

    // The previous right edge must first clear the screen's right edge. If the following
    // text is wider (and therefore moves faster over the same duration), also delay it long
    // enough that it cannot catch the previous comment before that one exits on the left.
    val previousClearMs =
        scrollDurationMs * previous.width / (viewportWidth + previous.width)
    val noCatchUpMs =
        scrollDurationMs * next.width / (viewportWidth + next.width)
    return gapMs >= max(previousClearMs, noCatchUpMs).toLong()
}

/**
 * Under 减弱动态效果 a comment that would cross the screen holds still instead: laid out as the
 * overlay's own top lines are, centred, for as long as those stay. 一起看's chat danmaku already
 * stopped for the setting; these kept flying.
 */
internal fun DanmakuComment.heldStill(reduceMotion: Boolean): DanmakuComment =
    if (reduceMotion && kind == DanmakuKind.Scroll) copy(kind = DanmakuKind.Top) else this

/**
 * A held comment's opacity [elapsedMs] into its [durationMs]: it arrives and leaves on a
 * [fadeMs] fade, since it no longer travels in or out.
 */
internal fun danmakuHeldAlpha(
    elapsedMs: Long,
    durationMs: Long,
    fadeMs: Long,
): Float {
    if (elapsedMs !in 0L..durationMs) return 0f
    if (fadeMs <= 0L) return 1f
    val edge = minOf(elapsedMs, durationMs - elapsedMs)
    return (edge.toFloat() / fadeMs).coerceIn(0f, 1f)
}

/**
 * How opaque a flying comment is drawn [elapsedMs] into its [durationMs]: wholly while it is on
 * screen and not at all either side of that, or on [danmakuHeldAlpha]'s fade when 减弱动态效果
 * holds it still.
 */
internal fun danmakuDrawAlpha(
    elapsedMs: Long,
    durationMs: Long,
    reduceMotion: Boolean,
): Float =
    when {
        reduceMotion -> danmakuHeldAlpha(elapsedMs, durationMs, Motion.REDUCED_FADE.toLong())
        elapsedMs in 0L..durationMs -> 1f
        else -> 0f
    }

internal fun lowerBoundDanmaku(
    comments: List<DanmakuComment>,
    timeMs: Long,
): Int {
    var low = 0
    var high = comments.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (comments[middle].timeMs < timeMs) low = middle + 1 else high = middle
    }
    return low
}

/**
 * Playback-position-driven overlay. Engine position updates only correct a real jump; ordinary
 * 500 ms engine ticks no longer restart the frame interpolator and make comments stutter.
 */
@Composable
internal fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    playing: Boolean,
    playbackRate: Float,
    displayArea: DanmakuDisplayArea,
    fontSize: DanmakuFontSize,
    speed: DanmakuSpeed,
    opacity: DanmakuOpacity,
    modifier: Modifier = Modifier,
    /** 点弹幕: what a finger can stop. Null leaves the comments untouchable, as they always were. */
    picker: DanmakuPicker? = null,
) {
    var renderedPositionMs by remember { mutableLongStateOf(positionMs) }
    var lastReportedPositionMs by remember { mutableLongStateOf(positionMs) }
    val latestReportedPosition by rememberUpdatedState(positionMs)
    val latestPlaybackRate by rememberUpdatedState(playbackRate)
    val recoveryRevision by DanmakuRuntimeRecoveryFence.revision.collectAsState()
    var observedRecoveryRevision by remember { mutableLongStateOf(recoveryRevision) }
    var recoveryFence by remember { mutableStateOf(DanmakuRecoveryFenceState()) }

    LaunchedEffect(positionMs, playing, recoveryRevision) {
        if (recoveryRevision != observedRecoveryRevision) {
            observedRecoveryRevision = recoveryRevision
            recoveryFence = armDanmakuRecoveryFence(renderedPositionMs, positionMs)
        }

        val recovery =
            updateDanmakuRecoveryFence(
                state = recoveryFence,
                reportedPositionMs = positionMs,
                renderedPositionMs = renderedPositionMs,
            )
        recoveryFence = recovery.state
        recovery.holdAtMs?.let { highWater ->
            renderedPositionMs = max(renderedPositionMs, highWater)
            lastReportedPositionMs = positionMs
            return@LaunchedEffect
        }
        recovery.resumeAtMs?.let { resumeAt ->
            renderedPositionMs = max(renderedPositionMs, resumeAt)
        }

        when {
            isDanmakuBackwardSeek(lastReportedPositionMs, positionMs) -> renderedPositionMs = positionMs
            positionMs > renderedPositionMs + POSITION_RESET_THRESHOLD_MS -> renderedPositionMs = positionMs
            // Pauses and brief transport stalls freeze the engine's reported clock. They are not
            // backward seeks and therefore must never make already-consumed danmaku replay.
            !playing -> Unit
        }
        lastReportedPositionMs = positionMs
        picker?.state?.settle(renderedPositionMs)
    }
    LaunchedEffect(playing, recoveryRevision) {
        if (!playing) return@LaunchedEffect
        if (recoveryRevision != observedRecoveryRevision) {
            observedRecoveryRevision = recoveryRevision
            recoveryFence = armDanmakuRecoveryFence(renderedPositionMs, latestReportedPosition)
        }
        var previousFrame = withFrameMillis { it }
        while (isActive) {
            withFrameMillis { frameTime ->
                val elapsed = frameTime - previousFrame
                previousFrame = frameTime
                val reported = latestReportedPosition

                val recovery =
                    updateDanmakuRecoveryFence(
                        state = recoveryFence,
                        reportedPositionMs = reported,
                        renderedPositionMs = renderedPositionMs,
                    )
                recoveryFence = recovery.state
                recovery.holdAtMs?.let { highWater ->
                    renderedPositionMs = max(renderedPositionMs, highWater)
                    return@withFrameMillis
                }
                recovery.resumeAtMs?.let { resumeAt ->
                    renderedPositionMs = max(renderedPositionMs, resumeAt)
                }

                renderedPositionMs =
                    advanceDanmakuInterpolatedPosition(
                        renderedPositionMs = renderedPositionMs,
                        reportedPositionMs = reported,
                        elapsedMs = elapsed,
                        playbackRate = latestPlaybackRate,
                    )
                if (reported > renderedPositionMs + POSITION_RESET_THRESHOLD_MS) {
                    renderedPositionMs = reported
                }
                picker?.state?.settle(renderedPositionMs)
            }
        }
    }
    // A comment stopped by 点弹幕 keeps its place in a new list that still holds it — a block of
    // something else, 合并重复, a refetch — and goes with one that does not, or with the overlay:
    // its menu must not outlive the comments it was opened over.
    LaunchedEffect(comments, picker) { picker?.state?.retain(comments::containsDanmaku) }
    DisposableEffect(picker) { onDispose { picker?.state?.drop() } }
    SideEffect { picker?.state?.clock = { renderedPositionMs } }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion

    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(displayArea.fraction)
                .clipToBounds()
                .onPlaced { picker?.laneFrame = it },
        ) {
            val textSize = 18f * fontSize.scale
            val textStyle =
                remember(textSize) {
                    sc(textSize, 650).copy(
                        shadow =
                            Shadow(
                                color = Color.Black.copy(alpha = 0.9f),
                                offset = Offset(1.5f, 1.5f),
                                blurRadius = 3f,
                            ),
                    )
                }
            val laneHeight = (textSize + 10f).dp
            val laneCount = (maxHeight / laneHeight).toInt().coerceAtLeast(1)
            val viewportWidth = maxWidth.value
            // Layouts by the text drawn and lanes by the comment, not by list position, so neither is
            // lost when a block, 合并重复 or a refetch hands over a new list. A line is laid out once
            // and drawn from that layout on every frame after, and the width its lane is dealt by is
            // that layout's own, so what is drawn and what a finger is tested against cannot differ.
            val layoutCache =
                remember(textStyle, density, textMeasurer) {
                    HashMap<String, TextLayoutResult>()
                }
            val laneCache =
                remember(
                    speed,
                    laneCount,
                    maxWidth,
                    textStyle,
                    density,
                    reduceMotion,
                ) {
                    HashMap<DanmakuKey, Int>()
                }
            val timeBucket by remember {
                derivedStateOf { renderedPositionMs.floorDiv(WINDOW_BUCKET_MS) }
            }
            // The two caches carry the text style and density: a new one of either deals again.
            val window =
                remember(
                    comments,
                    timeBucket,
                    speed,
                    laneCount,
                    maxWidth,
                    reduceMotion,
                    laneCache,
                    layoutCache,
                ) {
                    val bucketStart = timeBucket * WINDOW_BUCKET_MS
                    val maxDuration = max(speed.durationMs, FIXED_DURATION_MS)
                    val windowStartMs = (bucketStart - maxDuration).coerceAtLeast(0L)
                    val windowEndMs = bucketStart + WINDOW_BUCKET_MS
                    val from = lowerBoundDanmaku(comments, windowStartMs)
                    val until = lowerBoundDanmaku(comments, windowEndMs)
                    // Only the window's lanes are ever read, so the rest are forgotten as it moves
                    // on; clearing them all would re-deal the comments still crossing.
                    laneCache.keys.removeAll { it.timeMs < windowStartMs || it.timeMs >= windowEndMs }
                    // Past the cap, every layout but the window's own goes: laying a line out is the
                    // costly part, and the window's lines are drawn again on the very next frame.
                    if (layoutCache.size > MAX_CACHED_DANMAKU_LAYOUTS) {
                        val inWindow = (from until until).mapTo(HashSet()) { comments[it].displayText }
                        layoutCache.keys.retainAll(inWindow)
                    }
                    val keys = danmakuKeysIn(comments, from, until)
                    val layouts = ArrayList<TextLayoutResult>(until - from)
                    val inputs = ArrayList<DanmakuLayoutInput>(until - from)
                    for (index in from until until) {
                        val comment = comments[index]
                        val text = comment.displayText
                        val layout =
                            layoutCache.getOrPut(text) {
                                textMeasurer.measure(
                                    text = AnnotatedString(text),
                                    style = textStyle,
                                    maxLines = 1,
                                )
                            }
                        val width = with(density) { layout.size.width.toDp() }
                        layouts.add(layout)
                        inputs.add(
                            DanmakuLayoutInput(
                                index = index,
                                comment = comment.heldStill(reduceMotion),
                                width = width.value,
                                key = keys[index - from],
                            ),
                        )
                    }
                    val placements =
                        allocateDanmakuLanes(
                            inputs = inputs,
                            laneCount = laneCount,
                            viewportWidth = viewportWidth,
                            scrollDurationMs = speed.durationMs,
                            laneCache = laneCache,
                            maxPerLane = MAX_DANMAKU_PER_LANE,
                        )
                    DanmakuWindow(placements, placements.map { layouts[it.input.index - from] })
                }

            SideEffect {
                picker?.density = density.density
                picker?.state?.layout =
                    DanmakuPickLayout(
                        placements = window.placements,
                        laneHeight = laneHeight.value,
                        viewportWidth = viewportWidth,
                        scrollDurationMs = speed.durationMs,
                        fixedDurationMs = FIXED_DURATION_MS,
                    )
            }
            val holds = picker?.state?.holds.orEmpty()

            // Every flying comment on one canvas, from the window's layouts. A Text apiece, each with
            // its own layer and blurred shadow, made a busy moment hundreds of nodes to place and
            // record on every frame; reading the clock only here keeps a frame to drawing alone.
            Canvas(Modifier.fillMaxSize()) {
                val nowMs = renderedPositionMs
                val placements = window.placements
                for (position in placements.indices) {
                    val placement = placements[position]
                    // A stopped comment is drawn by its hold below, from where it stopped.
                    if (holds.isNotEmpty() && holds.any { it.isFor(placement) }) continue
                    val comment = placement.input.comment
                    val duration = if (comment.kind == DanmakuKind.Scroll) speed.durationMs else FIXED_DURATION_MS
                    val elapsed = nowMs - comment.timeMs
                    val fade = danmakuDrawAlpha(elapsed, duration, reduceMotion)
                    if (fade <= 0f) continue
                    val left = danmakuLeft(comment.kind, elapsed, duration, viewportWidth, placement.input.width)
                    drawText(
                        window.layouts[position],
                        color = Color(0xFF000000 or comment.color).copy(alpha = opacity.alpha),
                        // On whole pixels, where each comment's own layout node used to be placed.
                        topLeft =
                            Offset(
                                left.dp.roundToPx().toFloat(),
                                (laneHeight * placement.lane.toFloat()).roundToPx().toFloat(),
                            ),
                        alpha = fade,
                        // A layer's alpha faded the shadow along with the text; drawn straight onto
                        // the canvas, the shadow has to be faded with it by hand.
                        shadow = if (fade < 1f) textStyle.shadow?.fadedTo(fade) else null,
                    )
                }
            }
            holds.forEach { hold ->
                key(hold.key) { HeldDanmaku(hold, { renderedPositionMs }, textStyle, opacity, reduceMotion) }
            }
        }
    }
}

/** One window's placed comments, each beside the layout it is drawn from. */
private class DanmakuWindow(
    val placements: List<DanmakuLanePlacement>,
    val layouts: List<TextLayoutResult>,
)

/** This shadow at [fraction] of its own opacity, for a comment faded as a whole. */
private fun Shadow.fadedTo(fraction: Float): Shadow =
    Shadow(color = color.copy(alpha = color.alpha * fraction), offset = offset, blurRadius = blurRadius)

/**
 * The comment 点弹幕 stopped: still, opaque and on a plate while its menu is open, then flying
 * on from that spot like the rest — the menu's time is not taken out of its flight.
 */
@Composable
private fun HeldDanmaku(
    hold: DanmakuHold,
    renderedMs: () -> Long,
    style: TextStyle,
    opacity: DanmakuOpacity,
    reduceMotion: Boolean,
) {
    Text(
        text = hold.comment.displayText,
        maxLines = 1,
        color = Color(0xFF000000 or hold.comment.color).copy(alpha = if (hold.held) 1f else opacity.alpha),
        style = style,
        modifier =
            Modifier
                .offset { IntOffset(hold.leftAt(renderedMs()).dp.roundToPx(), hold.top.dp.roundToPx()) }
                .drawBehind {
                    if (hold.held) {
                        val pad = HeldPlatePadding.toPx()
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            topLeft = Offset(-pad, 0f),
                            size = Size(size.width + pad * 2f, size.height),
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.7f),
                            topLeft = Offset(-pad, 0f),
                            size = Size(size.width + pad * 2f, size.height),
                            cornerRadius = CornerRadius(size.height / 2f),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }.graphicsLayer {
                    val now = renderedMs()
                    val elapsed = hold.elapsedAt(now)
                    alpha =
                        when {
                            hold.held -> 1f
                            hold.finishedAt(now) -> 0f
                            reduceMotion ->
                                danmakuHeldAlpha(elapsed, hold.durationMs, Motion.REDUCED_FADE.toLong())
                            else -> 1f
                        }
                },
    )
}

/** How far the held comment's plate reaches past its text on either side. */
private val HeldPlatePadding = 8.dp
