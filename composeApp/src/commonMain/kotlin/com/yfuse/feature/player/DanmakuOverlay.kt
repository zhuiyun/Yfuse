package com.yfuse.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuKind
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.designsystem.sc
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

private const val FIXED_DURATION_MS = 4_000L
private const val POSITION_RESET_THRESHOLD_MS = 1_000L
private const val WINDOW_BUCKET_MS = 1_000L

internal data class DanmakuLayoutInput(
    val index: Int,
    val comment: DanmakuComment,
    /** Measured text width in the same units as [allocateDanmakuLanes]' viewport. */
    val width: Float,
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
 */
internal fun allocateDanmakuLanes(
    inputs: List<DanmakuLayoutInput>,
    laneCount: Int,
    viewportWidth: Float,
    scrollDurationMs: Long,
    fixedDurationMs: Long = FIXED_DURATION_MS,
    laneCache: MutableMap<Int, Int>? = null,
): List<DanmakuLanePlacement> {
    if (laneCount <= 0 || viewportWidth <= 0f) return emptyList()
    val tails = arrayOfNulls<LaneTail>(laneCount)
    return buildList {
        inputs.forEach { input ->
            val duration =
                if (input.comment.kind == DanmakuKind.Scroll) {
                    scrollDurationMs
                } else {
                    fixedDurationMs
                }
            val cachedLane = laneCache?.get(input.index)
            if (cachedLane != null) {
                if (cachedLane >= 0) {
                    tails[cachedLane] =
                        LaneTail(
                            startedAtMs = input.comment.timeMs,
                            width = input.width,
                            kind = input.comment.kind,
                            durationMs = duration,
                        )
                    add(DanmakuLanePlacement(input, cachedLane))
                }
                return@forEach
            }
            val laneOrder: IntProgression =
                if (input.comment.kind == DanmakuKind.Bottom) {
                    (laneCount - 1) downTo 0
                } else {
                    0 until laneCount
                }
            val lane =
                laneOrder.firstOrNull { candidate ->
                    canEnterLane(
                        previous = tails[candidate],
                        next = input,
                        viewportWidth = viewportWidth,
                        scrollDurationMs = scrollDurationMs,
                    )
                }
            if (lane == null) {
                laneCache?.put(input.index, -1)
                return@forEach
            }
            laneCache?.put(input.index, lane)
            tails[lane] =
                LaneTail(
                    startedAtMs = input.comment.timeMs,
                    width = input.width,
                    kind = input.comment.kind,
                    durationMs = duration,
                )
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
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    playing: Boolean,
    playbackRate: Float,
    displayArea: DanmakuDisplayArea,
    fontSize: DanmakuFontSize,
    speed: DanmakuSpeed,
    opacity: DanmakuOpacity,
    modifier: Modifier = Modifier,
) {
    var renderedPositionMs by remember { mutableLongStateOf(positionMs) }
    val latestReportedPosition by rememberUpdatedState(positionMs)
    val latestPlaybackRate by rememberUpdatedState(playbackRate)

    LaunchedEffect(positionMs, playing) {
        if (!playing || abs(positionMs - renderedPositionMs) > POSITION_RESET_THRESHOLD_MS) {
            renderedPositionMs = positionMs
        }
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var previousFrame = withFrameMillis { it }
        while (isActive) {
            withFrameMillis { frameTime ->
                val elapsed = frameTime - previousFrame
                previousFrame = frameTime
                renderedPositionMs += (elapsed * latestPlaybackRate).toLong()
                val reported = latestReportedPosition
                if (abs(reported - renderedPositionMs) > POSITION_RESET_THRESHOLD_MS) {
                    renderedPositionMs = reported
                }
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(displayArea.fraction)
                .clipToBounds(),
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
            val widthCache =
                remember(comments, textStyle, density) {
                    HashMap<Int, Float>()
                }
            val laneCache =
                remember(
                    comments,
                    speed,
                    laneCount,
                    maxWidth,
                    textStyle,
                    density,
                ) {
                    HashMap<Int, Int>()
                }
            val timeBucket = renderedPositionMs.floorDiv(WINDOW_BUCKET_MS)
            val placements =
                remember(
                    comments,
                    timeBucket,
                    speed,
                    laneCount,
                    maxWidth,
                    textStyle,
                    density,
                ) {
                    val bucketStart = timeBucket * WINDOW_BUCKET_MS
                    val maxDuration = max(speed.durationMs, FIXED_DURATION_MS)
                    val from =
                        lowerBoundDanmaku(
                            comments,
                            (bucketStart - maxDuration).coerceAtLeast(0L),
                        )
                    val until = lowerBoundDanmaku(comments, bucketStart + WINDOW_BUCKET_MS)
                    val inputs =
                        (from until until).map { index ->
                            val comment = comments[index]
                            val measuredWidth =
                                widthCache.getOrPut(index) {
                                    with(density) {
                                        textMeasurer
                                            .measure(
                                                text = AnnotatedString(comment.displayText),
                                                style = textStyle,
                                                maxLines = 1,
                                            ).size.width
                                            .toDp()
                                            .value
                                    }
                                }
                            DanmakuLayoutInput(index, comment, measuredWidth)
                        }
                    allocateDanmakuLanes(
                        inputs = inputs,
                        laneCount = laneCount,
                        viewportWidth = maxWidth.value,
                        scrollDurationMs = speed.durationMs,
                        laneCache = laneCache,
                    )
                }

            placements.forEach { placement ->
                val comment = placement.input.comment
                val duration =
                    if (comment.kind == DanmakuKind.Scroll) {
                        speed.durationMs
                    } else {
                        FIXED_DURATION_MS
                    }
                val elapsed = renderedPositionMs - comment.timeMs
                if (elapsed !in 0..duration) return@forEach

                key(placement.input.index, comment.timeMs, comment.displayText) {
                    val measuredWidth = placement.input.width.dp
                    val x =
                        when (comment.kind) {
                            DanmakuKind.Scroll -> {
                                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                                maxWidth - (maxWidth + measuredWidth) * progress
                            }
                            DanmakuKind.Top,
                            DanmakuKind.Bottom,
                            -> (maxWidth - measuredWidth).coerceAtLeast(0.dp) / 2f
                        }
                    val y = laneHeight * placement.lane.toFloat()
                    Text(
                        text = comment.displayText,
                        maxLines = 1,
                        color =
                            Color(0xFF000000 or comment.color)
                                .copy(alpha = opacity.alpha),
                        style = textStyle,
                        modifier = Modifier.offset(x = x, y = y),
                    )
                }
            }
        }
    }
}
