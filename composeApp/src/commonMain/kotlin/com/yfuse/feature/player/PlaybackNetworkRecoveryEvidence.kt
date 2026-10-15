package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackMediaProbe

/** A seek clock tick is not recovery; expected sinks must produce output and the media must advance. */
internal fun PlaybackState.networkRecoveryOutcome(
    resumePositionMs: Long,
    probe: PlaybackMediaProbe,
): Boolean? {
    if (error != null) return false
    if (buffering) return null
    val expected = diagnostics.outputExpectation(probe, videoHeight, audioTracks.isNotEmpty())

    fun ready(readiness: PlaybackOutputReadiness): Boolean =
        readiness == PlaybackOutputReadiness.Rendering || readiness == PlaybackOutputReadiness.Unknown
    if (expected.video && !ready(diagnostics.effectiveVideoReadiness)) return null
    if (expected.audio && !ready(diagnostics.effectiveAudioReadiness)) return null
    if (ended) return true
    return true.takeIf { playing && positionMs - resumePositionMs >= RECOVERY_OUTPUT_PROGRESS_MS }
}

private const val RECOVERY_OUTPUT_PROGRESS_MS = 3_000L

internal data class PlaybackOutputExpectation(
    val video: Boolean,
    val audio: Boolean,
)

/** Prefer reported track/output facts; when everything is unknown, treat a player item as video. */
internal fun PlaybackDiagnostics.outputExpectation(
    probe: PlaybackMediaProbe,
    videoHeight: Int,
    hasAudioTracks: Boolean,
): PlaybackOutputExpectation {
    val evidence = outputEvidence
    val videoKnown =
        probe.source.videoCodec != null ||
            probe.source.width != null ||
            probe.source.height != null ||
            videoHeight > 0 ||
            videoWidth > 0 ||
            evidence.videoDecoder.isNotBlank() ||
            evidence.videoCodecProfile.isNotBlank() ||
            effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
    val audioKnown =
        probe.audioCodec != null ||
            hasAudioTracks ||
            audioFormat.isNotBlank() ||
            evidence.audioDecoder.isNotBlank() ||
            evidence.audioMode != PlaybackAudioOutputMode.Unknown ||
            effectiveAudioReadiness == PlaybackOutputReadiness.Rendering
    return PlaybackOutputExpectation(
        video = videoKnown || !audioKnown,
        audio = audioKnown,
    )
}
