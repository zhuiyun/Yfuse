package com.yfuse.core2.android

internal enum class YAudioClockFrameSource {
    Timestamp,
    PlaybackHead,
}

internal data class YAudioClockFrameSelection(
    val framePosition: Long,
    val realtimeNs: Long,
    val source: YAudioClockFrameSource,
)

/**
 * Rejects an OEM audio clock that is present but not advancing while AudioTrack is playing.
 *
 * Android documents that AudioTimestamp can remain temporarily unavailable or stationary during
 * warm-up and route changes. A stationary timestamp must not become YCore's permanent master
 * clock: once both the hardware timestamp and playback head have failed to advance beyond the
 * grace period, callers fall back to the media wall clock until either audio counter recovers.
 */
internal class AndroidAudioClockProgressGuard(
    private val staleAfterNs: Long = DEFAULT_AUDIO_CLOCK_STALE_AFTER_NS,
) {
    private var lastTimestampFrames: Long? = null
    private var lastTimestampProgressNs = 0L
    private var lastPlaybackHeadFrames: Long? = null
    private var lastPlaybackHeadProgressNs = 0L

    init {
        require(staleAfterNs > 0L)
    }

    fun select(
        nowNs: Long,
        playing: Boolean,
        timestampFrames: Long?,
        timestampRealtimeNs: Long?,
        playbackHeadFrames: Long,
    ): YAudioClockFrameSelection? {
        observeTimestamp(timestampFrames, nowNs)
        observePlaybackHead(playbackHeadFrames, nowNs)

        if (!playing) {
            return timestampFrames?.let { frames ->
                YAudioClockFrameSelection(
                    framePosition = frames,
                    realtimeNs = timestampRealtimeNs ?: nowNs,
                    source = YAudioClockFrameSource.Timestamp,
                )
            } ?: YAudioClockFrameSelection(
                framePosition = playbackHeadFrames,
                realtimeNs = nowNs,
                source = YAudioClockFrameSource.PlaybackHead,
            )
        }

        if (
            timestampFrames != null &&
            timestampRealtimeNs != null &&
            nowNs - lastTimestampProgressNs <= staleAfterNs
        ) {
            return YAudioClockFrameSelection(
                framePosition = timestampFrames,
                realtimeNs = timestampRealtimeNs,
                source = YAudioClockFrameSource.Timestamp,
            )
        }
        if (nowNs - lastPlaybackHeadProgressNs <= staleAfterNs) {
            return YAudioClockFrameSelection(
                framePosition = playbackHeadFrames,
                realtimeNs = nowNs,
                source = YAudioClockFrameSource.PlaybackHead,
            )
        }
        return null
    }

    fun reset() {
        lastTimestampFrames = null
        lastTimestampProgressNs = 0L
        lastPlaybackHeadFrames = null
        lastPlaybackHeadProgressNs = 0L
    }

    private fun observeTimestamp(
        frames: Long?,
        nowNs: Long,
    ) {
        if (frames == null) return
        if (lastTimestampFrames == null || lastTimestampFrames != frames) {
            lastTimestampFrames = frames
            lastTimestampProgressNs = nowNs
        }
    }

    private fun observePlaybackHead(
        frames: Long,
        nowNs: Long,
    ) {
        if (lastPlaybackHeadFrames == null || lastPlaybackHeadFrames != frames) {
            lastPlaybackHeadFrames = frames
            lastPlaybackHeadProgressNs = nowNs
        }
    }
}

private const val DEFAULT_AUDIO_CLOCK_STALE_AFTER_NS = 500_000_000L
