package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import com.yfuse.core2.api.normalizeBufferedRanges

/** mediacodec_embed can report zero decoded/surface frame counters while draw evidence advances. */
internal class SenPlayerVideoReadinessGate {
    private var source = 0L
    private var baseline = 0L
    private var lastDraws = 0L
    private var rendered = false

    fun reset(
        sourceId: Long = 0L,
        draws: Long = lastDraws,
    ) {
        source = sourceId
        baseline = draws.coerceAtLeast(0)
        lastDraws = baseline
        rendered = false
    }

    fun observe(
        sourceId: Long,
        draws: Long,
        width: Int,
        height: Int,
        surfaceReady: Boolean,
    ): Boolean {
        if (sourceId == 0L || sourceId != source || !surfaceReady) return false
        val current = draws.coerceAtLeast(0)
        // Treat an observed decrease as a reset; a positive count already includes a draw.
        // The exact JNI reset timing is not established by this policy's boundary tests.
        if (current < lastDraws) {
            baseline = 0
            rendered = false
        }
        lastDraws = current
        if (width > 0 && height > 0 && current > baseline) rendered = true
        return rendered && width > 0 && height > 0
    }
}

internal fun PlaybackState.forSenPlayerLoad(
    sessionId: String,
    generation: Long,
): PlaybackState =
    copy(
        playing = false,
        buffering = true,
        ended = false,
        error = null,
        errorKind = null,
        automaticFallbackBlocked = false,
        fallbacksExhausted = false,
        positionMs = positionMs.coerceAtLeast(0),
        durationMs = 0,
        videoHeight = 0,
        bufferedPositionMs = 0,
        bufferSnapshot = YBufferSnapshot(sessionId = sessionId, generation = generation),
        audioTracks = emptyList(),
        subtitleTracks = emptyList(),
        secondarySubtitleTrackId = null,
        secondarySubtitleOffsetMs = 0,
        chapters = emptyList(),
        diagnostics =
            PlaybackDiagnostics(
                engine = diagnostics.engine,
                decoder = "等待 SenPlayer 视频轨道",
                videoReadiness = PlaybackOutputReadiness.Waiting,
                audioReadiness = PlaybackOutputReadiness.Unknown,
            ),
    )

internal fun PlaybackState.forSenPlayerFailure(
    message: String,
    kind: PlaybackFailureKind,
): PlaybackState =
    copy(
        playing = false,
        buffering = false,
        ended = false,
        error = message,
        errorKind = kind,
        bufferedPositionMs = 0,
        bufferSnapshot = YBufferSnapshot(sessionId = bufferSnapshot.sessionId, generation = bufferSnapshot.generation),
        automaticFallbackBlocked = true,
        fallbacksExhausted = true,
        diagnostics =
            diagnostics.copy(
                videoReadiness = PlaybackOutputReadiness.Unknown,
                audioReadiness = PlaybackOutputReadiness.Unknown,
                bufferedDurationMs = 0,
                outputEvidence = PlaybackOutputEvidence(),
            ),
    )

internal fun senPlayerSeekPosition(
    positionMs: Long,
    durationMs: Long,
): Long = positionMs.coerceAtLeast(0).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }

internal fun senPlayerPendingSeekTarget(
    positionMs: Long?,
    durationMs: Long,
    phase: String,
): Long? =
    positionMs
        ?.takeIf {
            durationMs > 0 && phase in setOf("playing", "paused", "buffering", "seeking")
        }?.let { senPlayerSeekPosition(it, durationMs) }

internal fun senPlayerReportedPosition(
    pendingSeekMs: Long?,
    nativePositionMs: Long,
    phase: String,
): Long =
    (if (phase == "loading") pendingSeekMs ?: nativePositionMs else nativePositionMs)
        .coerceAtLeast(0)

internal data class SenPlayerCacheState(
    val snapshot: YBufferSnapshot,
    val endMs: Long,
    val durationMs: Long,
)

internal fun senPlayerCacheState(
    sessionId: String,
    generation: Long,
    available: Boolean,
    ranges: List<LongArray>,
    positionMs: Long,
    durationMs: Long,
    cacheEndMs: Long,
    cacheDurationMs: Long,
): SenPlayerCacheState =
    SenPlayerCacheState(
        snapshot =
            YBufferSnapshot(
                sessionId = sessionId,
                generation = generation,
                source = YBufferRangeSource.SenPlayerNative,
                coverage = if (available) YBufferRangeCoverage.SeekableRanges else YBufferRangeCoverage.Unknown,
                ranges =
                    if (available) {
                        normalizeBufferedRanges(
                            ranges.filter { it.size == 2 }.map { YBufferedRange(it[0], it[1]) },
                            durationMs,
                        )
                    } else {
                        emptyList()
                    },
            ),
        endMs = if (available) cacheEndMs.coerceAtLeast(positionMs.coerceAtLeast(0)) else positionMs.coerceAtLeast(0),
        // Progress.c is the native queued duration; the end of a distant island is not continuous buffer.
        durationMs = if (available) cacheDurationMs.coerceAtLeast(0) else 0,
    )

/** Natural EOF is consumed once; pausing at the boundary must never start the next source. */
internal class SenPlayerEndGate {
    private var consumed = false

    fun reset() {
        consumed = false
    }

    fun advance(
        ended: Boolean,
        requested: Boolean,
        autoNext: Boolean,
        pauseAtEnd: Boolean,
        hasNext: Boolean,
    ): Boolean {
        if (!ended || consumed) return false
        consumed = true
        return requested && autoNext && !pauseAtEnd && hasNext
    }
}
