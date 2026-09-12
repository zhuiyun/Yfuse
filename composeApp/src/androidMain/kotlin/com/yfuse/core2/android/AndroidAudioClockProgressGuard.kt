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
    private val playbackHeadCounter = AudioFrameCounter()
    private val timestampCounter = AudioFrameCounter()

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
        val extendedPlaybackHeadFrames = playbackHeadCounter.extend(playbackHeadFrames)
        val extendedTimestampFrames = timestampFrames?.let { timestampCounter.extend(it, extendedPlaybackHeadFrames) }
        observeTimestamp(extendedTimestampFrames, nowNs)
        observePlaybackHead(extendedPlaybackHeadFrames, nowNs)

        if (!playing) {
            return extendedTimestampFrames?.let { frames ->
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
            extendedTimestampFrames != null &&
            extendedTimestampFrames > 0L &&
            timestampRealtimeNs != null &&
            nowNs - lastTimestampProgressNs <= staleAfterNs
        ) {
            return YAudioClockFrameSelection(
                framePosition = extendedTimestampFrames,
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
        playbackHeadCounter.reset()
        timestampCounter.reset()
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

/** Both the playback head and some AudioTimestamp implementations wrap at 32 bits. */
private class AudioFrameCounter {
    private var previous: Long? = null
    private var epoch = 0L

    fun extend(
        frames: Long,
        referenceFrames: Long = frames,
    ): Long {
        val last = previous
        if (last == null) {
            // A timestamp may first become available after the playback head has wrapped.
            epoch = ((referenceFrames - frames + PLAYBACK_HEAD_PERIOD / 2L) / PLAYBACK_HEAD_PERIOD)
                .coerceAtLeast(0L) * PLAYBACK_HEAD_PERIOD
        } else if (last - frames > PLAYBACK_HEAD_PERIOD / 2L) {
            // Small OEM corrections are not wraps; configure/flush/release explicitly reset us.
            epoch += PLAYBACK_HEAD_PERIOD
        }
        previous = frames
        return epoch + frames
    }

    fun reset() {
        previous = null
        epoch = 0L
    }
}

private const val DEFAULT_AUDIO_CLOCK_STALE_AFTER_NS = 500_000_000L
private const val PLAYBACK_HEAD_PERIOD = 0x1_0000_0000L
