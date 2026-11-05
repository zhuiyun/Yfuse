package com.yfuse.feature.player

/** A reported interval of playable media time, not a byte-cache hit or a future prediction. */
data class PlaybackBufferedRange(val startMs: Long, val endMs: Long)

/** Preserve holes; only overlapping/touching intervals may be combined. */
internal fun normalizeBufferedRanges(
    ranges: List<PlaybackBufferedRange>,
    durationMs: Long,
): List<PlaybackBufferedRange> {
    val limit = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    val sorted = ranges.mapNotNull { range ->
        val start = range.startMs.coerceIn(0L, limit)
        val end = range.endMs.coerceIn(0L, limit)
        if (end > start) PlaybackBufferedRange(start, end) else null
    }.sortedBy { it.startMs }
    val merged = mutableListOf<PlaybackBufferedRange>()
    for (range in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && range.startMs <= previous.endMs) {
            merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, range.endMs))
        } else {
            merged += range
        }
    }
    return merged
}

/** Null means unsupported. An empty reported list means no known cached interval. */
internal fun displayedBufferedRanges(
    reported: List<PlaybackBufferedRange>?,
    positionMs: Long,
    bufferedEndMs: Long,
    durationMs: Long,
): List<PlaybackBufferedRange> =
    normalizeBufferedRanges(
        reported ?: listOf(PlaybackBufferedRange(positionMs, bufferedEndMs)),
        durationMs,
    )
