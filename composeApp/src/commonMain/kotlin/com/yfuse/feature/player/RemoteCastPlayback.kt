package com.yfuse.feature.player

import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.feature.player.contract.PlaybackEvidenceConfidence
import com.yfuse.feature.player.contract.PlaybackOutputEvidence
import com.yfuse.feature.player.contract.PlaybackOutputReadiness

/** Projects confirmed receiver facts into the existing engine-neutral control state. */
internal fun PlaybackState.withRemoteCast(
    cast: CastState,
    playMethod: String,
): PlaybackState {
    val receipt = cast.outputEvidence
    val outputConfirmed =
        cast.status == CastPlaybackStatus.Playing &&
            receipt.sessionRevision == cast.sessionRevision &&
            receipt.receiverConfirmed &&
            receipt.playbackConfirmed
    val outputReadiness =
        if (outputConfirmed) PlaybackOutputReadiness.Rendering else PlaybackOutputReadiness.Waiting
    val confidence =
        if (outputConfirmed) PlaybackEvidenceConfidence.Confirmed else PlaybackEvidenceConfidence.Requested
    return copy(
        playing =
            when (cast.status) {
                CastPlaybackStatus.Playing -> true
                CastPlaybackStatus.Error -> cast.lastRemoteWasPlaying
                else -> false
            },
        buffering =
            cast.status == CastPlaybackStatus.Connecting ||
                cast.status == CastPlaybackStatus.Buffering,
        positionMs = cast.positionMs.takeIf { cast.positionConfirmed } ?: positionMs,
        durationMs = cast.durationMs.takeIf { it > 0L } ?: durationMs,
        bufferedPositionMs = cast.positionMs.takeIf { cast.positionConfirmed } ?: bufferedPositionMs,
        videoHeight = 0,
        // Receiver command errors are actionable in the Cast panel, but are not a fatal
        // decoder/load error for the local engine and must not trigger its full-screen retry UI.
        error = null,
        ended = cast.status == CastPlaybackStatus.Ended,
        diagnostics =
            diagnostics.copy(
                engine = listOfNotNull("远程投屏", cast.activeDevice?.name).joinToString(" · "),
                decoder = "接收端未报告",
                videoCodec = "未知",
                playMethod = playMethod,
                videoWidth = 0,
                dynamicRange = "",
                audioFormat = "",
                videoOutput =
                    if (outputConfirmed) "Cast 接收端已开始输出" else "等待 Cast 视频输出回执",
                audioOutput =
                    if (outputConfirmed) "Cast 接收端已开始输出" else "等待 Cast 音频输出回执",
                videoReadiness = outputReadiness,
                audioReadiness = outputReadiness,
                dolbyVisionOutput = outputConfirmed && receipt.dolbyVisionOutput,
                dolbyAtmosOutput = outputConfirmed && receipt.dolbyAtmosOutput,
                dolbyVisionRpuApplied = false,
                dolbyVisionEnhancementLayerComposed = false,
                immersiveAudioCarrierOutput = false,
                spatialAudioOutput = false,
                headTrackingAvailable = false,
                deviceOutputCapabilities =
                    "Cast：DV ${cast.capabilities.dolbyVision.label} · Atmos ${cast.capabilities.dolbyAtmos.label}",
                bitrateBitsPerSecond = 0L,
                frameRate = 0f,
                droppedFrames = 0,
                bufferedDurationMs = 0L,
                networkBitsPerSecond = 0L,
                outputEvidence =
                    PlaybackOutputEvidence(
                        sessionRevision = cast.sessionRevision.coerceAtLeast(1L),
                        videoReadiness = outputReadiness,
                        audioReadiness = outputReadiness,
                        videoConfidence = confidence,
                        audioConfidence = confidence,
                        rendererDetail = receipt.detail,
                    ),
            ),
    )
}
