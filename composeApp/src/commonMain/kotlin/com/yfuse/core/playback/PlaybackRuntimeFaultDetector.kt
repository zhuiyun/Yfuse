package com.yfuse.core.playback

enum class PlaybackRuntimeFaultKind(
    val failureKind: PlaybackFailureKind,
) {
    StartupTimeout(PlaybackFailureKind.Decoder),
    RebufferTimeout(PlaybackFailureKind.Network),
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
    private val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
    private val rebufferTimeoutMs: Long = DEFAULT_REBUFFER_TIMEOUT_MS,
) {
    private var lastPositionMs = initialPositionMs
    private var lastProgressAtEpochMs = startedAtEpochMs
    private var outputHasBeenVerified = false
    private var unverifiableOutputHasAdvanced = false
    private var positionAdvancementWasExpected = false
    private var firstFrameWaitSinceEpochMs: Long? = startedAtEpochMs
    private var missingVideoSinceEpochMs: Long? = null
    private var missingAudioSinceEpochMs: Long? = null
    private var rebufferWaitSinceEpochMs: Long? = null
    private var reported = false

    init {
        require(startupTimeoutMs > 0L)
        require(rebufferTimeoutMs > 0L)
    }

    fun observe(observation: YCoreRuntimeObservation): PlaybackRuntimeFault? {
        if (reported) return null
        val now = observation.nowEpochMs.coerceAtLeast(startedAtEpochMs)
        var positionAdvanced = false
        if (observation.positionMs + BACKWARD_SEEK_THRESHOLD_MS < lastPositionMs) {
            lastPositionMs = observation.positionMs
            lastProgressAtEpochMs = now
        } else if (observation.positionMs > lastPositionMs + MIN_PROGRESS_STEP_MS) {
            lastPositionMs = observation.positionMs
            lastProgressAtEpochMs = now
            positionAdvanced = true
        }
        if (
            !observation.playbackRequested ||
            observation.errorPresent ||
            observation.ended
        ) {
            resetClocks(now)
            return null
        }

        val verifiedOutputPresent =
            (observation.videoExpected && observation.videoReady) ||
                (observation.audioExpected && observation.audioReady)
        if (verifiedOutputPresent) outputHasBeenVerified = true
        val renderedProgressMs = (observation.positionMs - initialPositionMs).coerceAtLeast(0L)
        val hasVerifiableExpectedOutput =
            (observation.videoExpected && observation.videoOutputVerifiable) ||
                (observation.audioExpected && observation.audioOutputVerifiable)
        if (!hasVerifiableExpectedOutput && renderedProgressMs >= MIN_PROGRESS_STEP_MS) {
            // Legacy adapters cannot report a rendered frame. For those adapters only, a moving
            // media position remains the best available proof that startup completed. NativeDirect
            // can verify output and must never use its synthetic clock as first-frame evidence.
            unverifiableOutputHasAdvanced = true
        }
        val outputHasStarted = outputHasBeenVerified || unverifiableOutputHasAdvanced
        val awaitingFirstOutput =
            !outputHasStarted &&
                (
                    (observation.videoExpected && !observation.videoReady) ||
                        (
                            !observation.videoExpected &&
                                observation.audioExpected &&
                                !observation.audioReady
                        )
                )

        // playbackRequested is user intent, not proof that the backend is currently advancing.
        // During an audio-focus pause some adapters can briefly keep that intent true while
        // PlaybackState.playing is already false. Once this binding has produced verified output,
        // that state is an intentional/externally imposed pause — not a renderer stall.
        // Keep the startup watchdog alive until the backend proves that output reached its sink.
        // A synthetic wall/audio clock may advance while MediaExtractor is still blocked on a
        // remote byte range, so position movement is not first-frame evidence.
        if (!observation.playing && !observation.buffering && outputHasStarted) {
            resetClocks(now)
            return null
        }

        val positionAdvancementExpected = observation.playing && !observation.buffering
        if (positionAdvancementExpected && !positionAdvancementWasExpected) {
            // A focus/lifecycle pause may last arbitrarily long. Resume owns a fresh stall budget.
            lastProgressAtEpochMs = now
        }
        positionAdvancementWasExpected = positionAdvancementExpected

        val videoMissing =
            observation.playing &&
                outputHasBeenVerified &&
                observation.videoExpected &&
                observation.videoOutputVerifiable &&
                !observation.videoReady &&
                renderedProgressMs >= MISSING_OUTPUT_PROGRESS_MS
        val audioMissing =
            observation.playing &&
                outputHasBeenVerified &&
                observation.audioExpected &&
                observation.audioOutputVerifiable &&
                !observation.audioReady &&
                renderedProgressMs >= MISSING_OUTPUT_PROGRESS_MS

        // Each clock is started when its condition begins and cleared the moment it stops,
        // every observation and independently of the others. Previously they were started
        // inside the branch that reported, so a condition that came back after recovering
        // was measured from the *first* time it appeared: the four-second grace had already
        // elapsed, and a second momentary dropout reported instantly. Since a report hands
        // playback to another backend, that turned one glitch into a switched engine.
        firstFrameWaitSinceEpochMs =
            if (awaitingFirstOutput) firstFrameWaitSinceEpochMs ?: now else null
        missingVideoSinceEpochMs = if (videoMissing) missingVideoSinceEpochMs ?: now else null
        missingAudioSinceEpochMs = if (audioMissing) missingAudioSinceEpochMs ?: now else null
        if (!observation.buffering) rebufferWaitSinceEpochMs = null

        // Buffering suppresses mid-play stall/output judgements, but not an initial load that never
        // produces a format or first frame. Previously the observer stopped on the first buffering
        // sample, so a backend could spin forever without entering the fallback chain.
        if (observation.buffering) {
            lastProgressAtEpochMs = now
            missingVideoSinceEpochMs = null
            missingAudioSinceEpochMs = null
            rebufferWaitSinceEpochMs =
                if (outputHasStarted && !positionAdvanced) {
                    rebufferWaitSinceEpochMs ?: now
                } else {
                    null
                }
            val fault =
                when {
                    awaitingFirstOutput && now.heldSince(firstFrameWaitSinceEpochMs) >= startupTimeoutMs ->
                        PlaybackRuntimeFault(
                            PlaybackRuntimeFaultKind.StartupTimeout,
                            "内核持续缓冲但未在限定时间内输出首帧",
                        )
                    outputHasStarted &&
                        now.heldSince(rebufferWaitSinceEpochMs) >= rebufferTimeoutMs ->
                        PlaybackRuntimeFault(
                            PlaybackRuntimeFaultKind.RebufferTimeout,
                            "播放中持续缓冲且媒体时钟没有前进",
                        )
                    else -> null
                }
            if (fault != null) reported = true
            return fault
        }

        val fault =
            when {
                awaitingFirstOutput && now.heldSince(firstFrameWaitSinceEpochMs) >= startupTimeoutMs ->
                    PlaybackRuntimeFault(
                        PlaybackRuntimeFaultKind.StartupTimeout,
                        "内核在限定时间内未输出首帧",
                    )
                videoMissing && now.heldSince(missingVideoSinceEpochMs) >= MISSING_OUTPUT_GRACE_MS ->
                    PlaybackRuntimeFault(
                        PlaybackRuntimeFaultKind.VideoOutputMissing,
                        "播放进度前进但没有可验证的视频输出",
                    )
                audioMissing && now.heldSince(missingAudioSinceEpochMs) >= MISSING_OUTPUT_GRACE_MS ->
                    PlaybackRuntimeFault(
                        PlaybackRuntimeFaultKind.AudioOutputMissing,
                        "播放进度前进但没有可验证的音频输出",
                    )
                observation.playing &&
                    outputHasStarted &&
                    !awaitingFirstOutput &&
                    (observation.videoExpected || observation.audioExpected) &&
                    now - lastProgressAtEpochMs >= POSITION_STALL_TIMEOUT_MS ->
                    PlaybackRuntimeFault(
                        PlaybackRuntimeFaultKind.PositionStalled,
                        "非缓冲状态下播放进度持续停滞",
                    )
                else -> null
            }
        if (fault != null) reported = true
        return fault
    }

    private fun resetClocks(nowEpochMs: Long) {
        lastProgressAtEpochMs = nowEpochMs
        firstFrameWaitSinceEpochMs = null
        missingVideoSinceEpochMs = null
        missingAudioSinceEpochMs = null
        rebufferWaitSinceEpochMs = null
        positionAdvancementWasExpected = false
    }
}

/** How long a condition has held, or zero when its clock is not running. */
private fun Long.heldSince(startedAtEpochMs: Long?): Long = startedAtEpochMs?.let { this - it } ?: 0L

private const val MIN_PROGRESS_STEP_MS = 250L
private const val BACKWARD_SEEK_THRESHOLD_MS = 1_000L
private const val MISSING_OUTPUT_PROGRESS_MS = 3_000L
private const val MISSING_OUTPUT_GRACE_MS = 4_000L
private const val DEFAULT_STARTUP_TIMEOUT_MS = 15_000L
private const val DEFAULT_REBUFFER_TIMEOUT_MS = 45_000L
private const val POSITION_STALL_TIMEOUT_MS = 12_000L
