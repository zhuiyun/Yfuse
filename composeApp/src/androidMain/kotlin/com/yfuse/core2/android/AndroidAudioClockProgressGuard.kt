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
    private var lastRawPlaybackHeadFrames: Long? = null
    private var playbackHeadEpoch = 0L

    init {
        require(staleAfterNs > 0L)
    }

    @Synchronized
    fun select(
        nowNs: Long,
        playing: Boolean,
        timestampFrames: Long?,
        timestampRealtimeNs: Long?,
        playbackHeadFrames: Long,
    ): YAudioClockFrameSelection? {
        val extendedPlaybackHeadFrames = extendPlaybackHead(playbackHeadFrames)
        observeTimestamp(timestampFrames, nowNs)
        observePlaybackHead(extendedPlaybackHeadFrames, nowNs)

        if (!playing) {
            return timestampFrames?.let { frames ->
                YAudioClockFrameSelection(
                    framePosition = frames,
                    realtimeNs = timestampRealtimeNs ?: nowNs,
                    source = YAudioClockFrameSource.Timestamp,
                )
            } ?: YAudioClockFrameSelection(
                framePosition = extendedPlaybackHeadFrames,
                realtimeNs = nowNs,
                source = YAudioClockFrameSource.PlaybackHead,
            )
        }

        if (
            timestampFrames != null &&
            timestampFrames > 0L &&
            timestampRealtimeNs != null &&
            nowNs - lastTimestampProgressNs <= staleAfterNs
        ) {
            return YAudioClockFrameSelection(
                framePosition = timestampFrames,
                realtimeNs = timestampRealtimeNs,
                source = YAudioClockFrameSource.Timestamp,
            )
        }
        // A present zero counter is not rendered audio. Granting it a warm-up window creates
        // a false Rendering -> Waiting transition ~500 ms after each empty sink restart.
        if (extendedPlaybackHeadFrames > 0L && nowNs - lastPlaybackHeadProgressNs <= staleAfterNs) {
            return YAudioClockFrameSelection(
                framePosition = extendedPlaybackHeadFrames,
                realtimeNs = nowNs,
                source = YAudioClockFrameSource.PlaybackHead,
            )
        }
        return null
    }

    @Synchronized
    fun reset() {
        lastTimestampFrames = null
        lastTimestampProgressNs = 0L
        lastPlaybackHeadFrames = null
        lastPlaybackHeadProgressNs = 0L
        lastRawPlaybackHeadFrames = null
        playbackHeadEpoch = 0L
    }

    /** AudioTrack exposes an unsigned 32-bit head even during playback longer than one wrap. */
    private fun extendPlaybackHead(frames: Long): Long {
        val previous = lastRawPlaybackHeadFrames
        // A small OEM counter correction is not a wrap. Configure/flush/release call reset().
        if (previous != null && previous - frames > PLAYBACK_HEAD_PERIOD / 2L) {
            playbackHeadEpoch += PLAYBACK_HEAD_PERIOD
        }
        lastRawPlaybackHeadFrames = frames
        return playbackHeadEpoch + frames
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
private const val PLAYBACK_HEAD_PERIOD = 0x1_0000_0000L
