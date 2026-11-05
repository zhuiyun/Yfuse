package com.yfuse.core2.android

import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.forwardBufferSnapshot

/** Local files are readable, but that does not mean their full duration is retained in RAM. */
internal fun YExtractorReadAheadSnapshot.timestampBufferEndMs(
    positionMs: Long,
    durationMs: Long,
): Long {
    val end = maxOf(positionMs, bufferedThroughUs / 1_000L)
    return if (durationMs > 0L) end.coerceAtMost(durationMs) else end
}

/** Only media timestamps held by every required track are advertised, never a byte/bitrate estimate. */
internal fun YExtractorReadAheadSnapshot.timestampBufferSnapshot(
    sessionId: String,
    positionMs: Long,
    durationMs: Long,
): YBufferSnapshot =
    forwardBufferSnapshot(
        sessionId,
        generation,
        YBufferRangeSource.YCoreQueue,
        maxOf(positionMs, bufferedFromUs / 1_000L),
        bufferedThroughUs / 1_000L,
        durationMs,
    )

/** Enhanced FFmpeg queues use their own media timestamps, not playback position + queue span. */
internal fun YDemuxReadAheadSnapshot.timestampBufferSnapshot(
    sessionId: String,
    positionMs: Long,
    durationMs: Long,
): YBufferSnapshot =
    YBufferSnapshot(
        sessionId = sessionId,
        generation = generation,
        source = YBufferRangeSource.YCoreQueue,
        coverage = com.yfuse.core2.api.YBufferRangeCoverage.ForwardOnly,
        ranges = bufferedRanges,
    ).atPlaybackPosition(positionMs, durationMs)

internal fun YDemuxReadAheadSnapshot.timestampBufferEndMs(
    positionMs: Long,
    durationMs: Long,
): Long {
    val end = maxOf(positionMs, bufferedThroughUs / 1_000L)
    return if (durationMs > 0L) end.coerceAtMost(durationMs) else end
}
