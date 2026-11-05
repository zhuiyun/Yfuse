package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Paint the base, buffered intervals, and played gradient with shared chapter gaps. */
internal fun DrawScope.drawSeekRail(
    color: Color,
    played: Float,
    cachedRanges: List<PlaybackBufferedRange>,
    durationMs: Long,
    chapterCuts: List<Float>,
) {
    val playedWidth = size.width * played
    val chapterGap = SeekChapterGap.toPx()
    val bufferColor = Color.White.copy(alpha = 0.60f)
    // One brush across every part, so the played gradient runs on through the cuts.
    val playedBrush =
        Brush.horizontalGradient(
            listOf(lerp(color, Color.Black, 0.14f), lerp(color, Color.White, 0.24f)),
            endX = playedWidth.coerceAtLeast(1f),
        )
    // Without chapters this is one part from end to end: the rail as it always was.
    forEachRailPart(size.width, chapterCuts, chapterGap) { left, right ->
        drawRect(
            Color.White.copy(alpha = 0.16f),
            topLeft = Offset(left, 0f),
            size = Size(right - left, size.height),
        )
        for (range in cachedRanges) {
            val cachedLeft =
                maxOf(
                    left,
                    size.width * playbackProgressFraction(range.startMs, durationMs),
                )
            val cachedRight =
                min(
                    right,
                    size.width * playbackProgressFraction(range.endMs, durationMs),
                )
            if (cachedRight > cachedLeft) {
                drawRect(
                    bufferColor,
                    topLeft = Offset(cachedLeft, 0f),
                    size = Size(cachedRight - cachedLeft, size.height),
                )
            }
        }
        val playedRight = min(right, playedWidth)
        if (playedRight > left) {
            drawRect(
                playedBrush,
                topLeft = Offset(left, 0f),
                size = Size(playedRight - left, size.height),
            )
        }
    }
}

/**
 * Calls [part] for each stretch of a [width]-wide rail between the chapter [cuts] (sorted
 * fractions), leaving [gap] pixels open at every cut. No cuts is a single part, end to end.
 */
private inline fun forEachRailPart(
    width: Float,
    cuts: List<Float>,
    gap: Float,
    part: (left: Float, right: Float) -> Unit,
) {
    var left = 0f
    for (cut in cuts) {
        val at = width * cut
        if (at - gap / 2f > left) part(left, at - gap / 2f)
        left = at + gap / 2f
    }
    if (width > left) part(left, width)
}

/** The opening between two chapters on the rail: wide enough to read, too narrow to aim at. */
private val SeekChapterGap = 2.dp
