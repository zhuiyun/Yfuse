package com.yfuse.feature.player

import com.yfuse.core.model.SEN_PLAYER_DISPLAY_NAME
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import com.yfuse.core2.api.normalizeBufferedRanges

internal fun PlaybackState.forSenPlayerLoad(
    sessionId: String,
    generation: Long,
    item: PlayerMediaItem? = null,
): PlaybackState =
    copy(
        playing = false,
        buffering = true,
        ended = false,
        error = null,
        errorKind = null,
        automaticFallbackBlocked = false,
        fallbacksExhausted = false,
        transcoding = item?.startsWithServerTranscode() == true,
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
            initialPlaybackDiagnostics(
                engine = diagnostics.engine,
                decoder = "等待 $SEN_PLAYER_DISPLAY_NAME 视频轨道",
                item = item,
            ).copy(
                senPlayerPicture = diagnostics.senPlayerPicture?.awaitingApplication(),
                videoWidth = 0,
                videoReadiness = PlaybackOutputReadiness.Waiting,
                audioReadiness = PlaybackOutputReadiness.Unknown,
            ),
    )

/** Keep the opened URL and the displayed method on the same negotiated playback route. */
internal fun PlayerMediaItem.senPlayerPlaybackUrl(): String {
    require(drmConfiguration == null && activeVersion?.drmConfiguration == null) {
        "SenPlayer DRM is not integrated"
    }
    return if (startsWithServerTranscode()) transcodeUrl else url
}

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
                senPlayerPicture = diagnostics.senPlayerPicture?.awaitingApplication(),
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

/** A seek can be followed by EOF at the old position if the remote byte range was truncated. */
internal class SenPlayerRemoteEndRecovery {
    var attempted = false
        private set
    private var seekTargetMs: Long? = null

    fun onSeek(targetMs: Long) {
        seekTargetMs = targetMs
        attempted = false
    }

    fun observePosition(positionMs: Long) {
        val target = seekTargetMs ?: return
        if (kotlin.math.abs(target - positionMs) <= 2_000L) seekTargetMs = null
    }

    fun recoveryPosition(positionMs: Long, durationMs: Long): Long? {
        val resumeMs = seekTargetMs ?: positionMs
        if (attempted || !com.yfuse.core2.api.isPrematurePlaybackEnd(positionMs, durationMs)) return null
        attempted = true
        return resumeMs.coerceIn(0L, durationMs)
    }
}

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
