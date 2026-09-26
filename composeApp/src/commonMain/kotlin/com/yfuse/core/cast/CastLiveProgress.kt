package com.yfuse.core.cast

import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType

/**
 * A cast session as Android 16's live-update bar: one stretch for the title, a point where each
 * chapter starts, and what is left to watch.
 */
data class CastLiveProgress(
    /** The bar's length and fill in whole seconds: fine enough for a film, far from Int's limit. */
    val max: Int,
    val progress: Int,
    /** Chapter starts on the same scale, at most [CAST_LIVE_MAX_POINTS] and never at either end. */
    val points: List<Int>,
    val remainingMs: Long,
    /** 片头 or 片尾 while the position is inside one; null elsewhere. */
    val section: String?,
)

/** Android 16 draws the first four points of a progress bar and drops the rest. */
const val CAST_LIVE_MAX_POINTS = 4

/**
 * The bar for a session at [positionMs] of [durationMs], or null while the receiver has not said
 * how long the title is. [chapterStartsMs] are real chapters when the server has them; without
 * any, the 片头 and 片尾 markers in [segments] are the chapters there are.
 */
fun castLiveProgress(
    positionMs: Long,
    durationMs: Long,
    segments: List<PlaybackSegment> = emptyList(),
    chapterStartsMs: List<Long> = emptyList(),
): CastLiveProgress? {
    if (durationMs < 1_000L) return null
    val position = positionMs.coerceIn(0L, durationMs)
    val max = (durationMs / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val marks =
        chapterStartsMs.ifEmpty {
            segments.flatMap { segment ->
                when (segment.type) {
                    PlaybackSegmentType.Intro -> listOfNotNull(segment.startMs, segment.endMs)
                    PlaybackSegmentType.Credits -> listOf(segment.startMs)
                }
            }
        }
    val points =
        marks
            .map { (it / 1_000L).toInt() }
            .filter { it in 1 until max }
            .distinct()
            .sorted()
            .take(CAST_LIVE_MAX_POINTS)
    val section =
        segments
            .firstOrNull { it.contains(position, durationMs) }
            ?.let { if (it.type == PlaybackSegmentType.Intro) "片头" else "片尾" }
    return CastLiveProgress(
        max = max,
        progress = (position / 1_000L).toInt().coerceAtMost(max),
        points = points,
        remainingMs = durationMs - position,
        section = section,
    )
}

/** 「1:02:03」 or 「42:10」, for what is left of a title. */
fun castLiveClock(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds % 3_600L / 60L
    val rest = seconds % 60L
    val tail = rest.toString().padStart(2, '0')
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:$tail"
    } else {
        "$minutes:$tail"
    }
}
