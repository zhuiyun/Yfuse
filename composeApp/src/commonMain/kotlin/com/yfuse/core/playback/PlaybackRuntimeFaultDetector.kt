package com.yfuse.core.playback

enum class PlaybackRuntimeFaultKind(
    val failureKind: PlaybackFailureKind,
) {
    StartupTimeout(PlaybackFailureKind.Decoder),
    PositionStalled(PlaybackFailureKind.Renderer),
    VideoOutputMissing(PlaybackFailureKind.Renderer),
    AudioOutputMissing(PlaybackFailureKind.AudioSink),
}

data class PlaybackRuntimeFault(
    val kind: PlaybackRuntimeFaultKind,
    val reason: String,
)

/** Detects silent failures that do not necessarily produce a backend error callback. */
class PlaybackRuntimeFaultDetector(
    private val startedAtEpochMs: Long,
    private val initialPositionMs: Long,
) {
    private var lastPositionMs = initialPositionMs
    private var lastProgressAtEpochMs = startedAtEpochMs
    private var firstFrameWaitSinceEpochMs: Long? = startedAtEpochMs
    private var missingVideoSinceEpochMs: Long? = null
    private var missingAudioSinceEpochMs: Long? = null
    private var reported = false

    fun observe(observation: YCoreRuntimeObservation): PlaybackRuntimeFault? {
        if (reported) return null
        val now = observation.nowEpochMs.coerceAtLeast(startedAtEpochMs)
        if (observation.positionMs + BACKWARD_SEEK_THRESHOLD_MS < lastPositionMs) {
            lastPositionMs = observation.positionMs
            lastProgressAtEpochMs = now
        } else if (observation.positionMs > lastPositionMs + MIN_PROGRESS_STEP_MS) {
            lastPositionMs = observation.positionMs
            lastProgressAtEpochMs = now
        }
        if (
            !observation.playbackRequested ||
            observation.buffering ||
            observation.errorPresent ||
            observation.ended
        ) {
            lastProgressAtEpochMs = now
            firstFrameWaitSinceEpochMs = null
            missingVideoSinceEpochMs = null
            missingAudioSinceEpochMs = null
            return null
        }

        val renderedProgressMs = (observation.positionMs - initialPositionMs).coerceAtLeast(0L)
        val firstFrameWaitMs =
            if (observation.videoExpected && !observation.videoReady && renderedProgressMs == 0L) {
                val since =
                    firstFrameWaitSinceEpochMs
                        ?: now.also { firstFrameWaitSinceEpochMs = it }
                now - since
            } else {
                0L
            }
        val fault =
            when {
                observation.videoExpected &&
                    !observation.videoReady &&
                    renderedProgressMs == 0L &&
                    firstFrameWaitMs >= STARTUP_TIMEOUT_MS ->
                    PlaybackRuntimeFault(
                        PlaybackRuntimeFaultKind.StartupTimeout,
                        "内核在限定时间内未输出首帧",
                    )
                observation.videoExpected &&
                    !observation.videoReady &&
                    renderedProgressMs >= MISSING_OUTPUT_PROGRESS_MS -> {
                    val since = missingVideoSinceEpochMs ?: now.also { missingVideoSinceEpochMs = it }
                    if (now - since >= MISSING_OUTPUT_GRACE_MS) {
                        PlaybackRuntimeFault(
                            PlaybackRuntimeFaultKind.VideoOutputMissing,
                            "播放进度前进但没有可验证的视频输出",
                        )
                    } else {
                        null
                    }
                }
                observation.audioExpected &&
                    !observation.audioReady &&
                    renderedProgressMs >= MISSING_OUTPUT_PROGRESS_MS -> {
                    val since = missingAudioSinceEpochMs ?: now.also { missingAudioSinceEpochMs = it }
                    if (now - since >= MISSING_OUTPUT_GRACE_MS) {
                        PlaybackRuntimeFault(
                            PlaybackRuntimeFaultKind.AudioOutputMissing,
                            "播放进度前进但没有可验证的音频输出",
                        )
                    } else {
                        null
                    }
                }
                observation.videoReady && now - lastProgressAtEpochMs >= POSITION_STALL_TIMEOUT_MS ->
                    PlaybackRuntimeFault(
                        PlaybackRuntimeFaultKind.PositionStalled,
                        "非缓冲状态下播放进度持续停滞",
                    )
                else -> null
            }
        if (fault != null) reported = true
        return fault
    }
}

private const val MIN_PROGRESS_STEP_MS = 250L
private const val BACKWARD_SEEK_THRESHOLD_MS = 1_000L
private const val MISSING_OUTPUT_PROGRESS_MS = 3_000L
private const val MISSING_OUTPUT_GRACE_MS = 4_000L
private const val STARTUP_TIMEOUT_MS = 15_000L
private const val POSITION_STALL_TIMEOUT_MS = 12_000L
