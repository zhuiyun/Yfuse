package com.yfuse.core2.api

/** Timestamps reported by a backend, never inferred from downloaded bytes or bitrate. */
data class YBufferedRange(
    val startMs: Long,
    val endMs: Long,
)

enum class YBufferRangeSource {
    Unknown,
    MpvDemuxer,
    SenPlayerNative,
    Media3Forward,
    MdkForward,
    YCoreQueue,
}

/** Forward-only APIs cannot claim that earlier seeks remain cached. */
enum class YBufferRangeCoverage { Unknown, ForwardOnly, SeekableRanges }

data class YBufferSnapshot(
    /** Process-local owner, without a URL, account identifier or credential. */
    val sessionId: String = "",
    val generation: Long = 0L,
    val source: YBufferRangeSource = YBufferRangeSource.Unknown,
    val coverage: YBufferRangeCoverage = YBufferRangeCoverage.Unknown,
    val ranges: List<YBufferedRange> = emptyList(),
) {
    /** Reclip on every timeline sample; forward-only evidence never becomes retained history. */
    fun atPlaybackPosition(
        positionMs: Long,
        durationMs: Long,
    ): YBufferSnapshot =
        copy(
            ranges =
                normalizeBufferedRanges(
                    when (coverage) {
                        YBufferRangeCoverage.Unknown -> emptyList()
                        YBufferRangeCoverage.SeekableRanges -> ranges
                        YBufferRangeCoverage.ForwardOnly ->
                            ranges.map {
                                it.copy(
                                    startMs = maxOf(it.startMs, positionMs),
                                )
                            }
                    },
                    durationMs,
                ),
        )

    fun shifted(
        offsetMs: Long,
        durationMs: Long,
    ): YBufferSnapshot =
        copy(
            ranges =
                normalizeBufferedRanges(
                    normalizeBufferedRanges(ranges).map { range ->
                        YBufferedRange(
                            range.startMs.coerceAtMost(Long.MAX_VALUE - offsetMs.coerceAtLeast(0L)) +
                                offsetMs.coerceAtLeast(0L),
                            range.endMs.coerceAtMost(Long.MAX_VALUE - offsetMs.coerceAtLeast(0L)) +
                                offsetMs.coerceAtLeast(0L),
                        )
                    },
                    durationMs,
                ),
        )
}

/** Merge overlaps only. Even a short hole must stay visible. Invalid ranges are discarded. */
fun normalizeBufferedRanges(
    ranges: List<YBufferedRange>,
    durationMs: Long = 0L,
): List<YBufferedRange> {
    val sorted =
        ranges
            .take(256)
            .mapNotNull { range ->
                val start = range.startMs.coerceAtLeast(0L)
                val end = if (durationMs > 0L) range.endMs.coerceAtMost(durationMs) else range.endMs
                if (end > start) YBufferedRange(start, end) else null
            }.sortedBy(YBufferedRange::startMs)
    val result = mutableListOf<YBufferedRange>()
    for (range in sorted) {
        val last = result.lastOrNull()
        if (last != null && range.startMs <= last.endMs) {
            result[result.lastIndex] = last.copy(endMs = maxOf(last.endMs, range.endMs))
        } else {
            result += range
        }
    }
    return result
}

fun forwardBufferSnapshot(
    sessionId: String,
    generation: Long,
    source: YBufferRangeSource,
    positionMs: Long,
    endMs: Long,
    durationMs: Long,
): YBufferSnapshot =
    YBufferSnapshot(
        sessionId = sessionId,
        generation = generation,
        source = source,
        coverage = YBufferRangeCoverage.ForwardOnly,
        ranges = normalizeBufferedRanges(listOf(YBufferedRange(positionMs, endMs)), durationMs),
    )
