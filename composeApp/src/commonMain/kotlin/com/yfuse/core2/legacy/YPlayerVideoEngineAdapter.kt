package com.yfuse.core2.legacy

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.feature.player.EngineTrack
import com.yfuse.feature.player.PlaybackDiagnostics
import com.yfuse.feature.player.PlaybackAudioOutputMode
import com.yfuse.feature.player.PlaybackDynamicRangeOutputMode
import com.yfuse.feature.player.PlaybackEvidenceConfidence
import com.yfuse.feature.player.PlaybackOutputEvidence
import com.yfuse.feature.player.PlaybackOutputReadiness
import com.yfuse.feature.player.PlaybackState
import com.yfuse.feature.player.PlaybackVideoRenderApi
import com.yfuse.feature.player.VideoEngine
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * Temporary reverse bridge used while PlayerRoot presentation still speaks [VideoEngine].
 *
 * New Core2 players can be inserted behind today's UI without pretending to be Exo/mpv/MDK. The
 * adapter owns no playback policy and no renderer; it only translates the stable product contract
 * into the legacy presentation contract. Remove it after the remaining UI and backend-specific
 * queue surfaces consume [YPlayer] directly.
 */
internal class YPlayerVideoEngineAdapter(
    val player: YPlayer,
) : VideoEngine {
    override val state: StateFlow<PlaybackState> = player.asPlaybackStateFlow()

    override val playbackRequested: Boolean get() = player.playbackRequested

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun prepareForHandover() {
        player.pause()
        // Detach before Compose creates the replacement so the outgoing codec cannot retain an old
        // frame or keep an AudioTrack/video Surface active beside the next backend.
        player.setVideoOutput(null)
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setSpeed(speed: Float) = player.setSpeed(speed)

    override fun selectAudioTrack(id: String) = player.selectTrack(YTrackType.Audio, id)

    override fun selectSubtitleTrack(id: String) = player.selectTrack(YTrackType.Subtitle, id)

    // Core2 subtitles are presented by Core2Surface above the direct video Surface. Reporting
    // these capabilities here prevents the legacy compatibility layer from rebuilding Core2 as
    // MPV even though the requested presentation change has already been applied by Compose.
    override val supportsSubtitleOffset: Boolean = true

    override val supportsSubtitleScale: Boolean = true

    override val supportsSubtitleBrightness: Boolean = true

    override val supportsSubtitlePosition: Boolean = true

    override val supportsSubtitleAppearance: Boolean = true

    override fun setSubtitleOffsetMs(offsetMs: Long): Boolean = true

    override fun setSubtitleScale(scale: Float): Boolean = true

    override fun setSubtitleBrightness(brightness: Float): Boolean = true

    override fun setSubtitlePosition(position: Float): Boolean = true

    override fun setSubtitleAppearance(appearance: com.yfuse.feature.player.SubtitleAppearance): Boolean = true

    override fun selectItem(index: Int) = player.selectItem(index)

    override fun selectDiscTitle(index: Int): Boolean = player.selectDiscTitle(index)

    override fun selectDiscChapter(index: Int): Boolean = player.selectDiscChapter(index)

    override fun selectDiscAngle(index: Int): Boolean = player.selectDiscAngle(index)

    override fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean = player.sendDiscMenuCommand(command)

    override fun currentPositionMs(): Long = player.currentPositionMs()

    override fun retry() = player.retry()

    override fun release() = player.release()
}

/** Returns the product player without wrapping a Core2 player back through the Legacy contract. */
internal fun VideoEngine.asYPlayer(): YPlayer =
    if (this is YPlayerVideoEngineAdapter) {
        player
    } else {
        LegacyYPlayerAdapter(this)
    }

/**
 * Product presentation bridge used while YPlayerState intentionally omits Legacy-only extensions.
 * Legacy keeps its full state object; native Core2 state is translated once at the UI boundary.
 */
internal fun YPlayer.asPlaybackStateFlow(): StateFlow<PlaybackState> =
    if (this is LegacyYPlayerAdapter) {
        presentationState
    } else {
        ReverseMappedStateFlow(state, YPlayerState::toLegacyPlaybackState)
    }

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ReverseMappedStateFlow<Source, Target>(
    private val source: StateFlow<Source>,
    private val transform: (Source) -> Target,
) : StateFlow<Target> {
    override val value: Target get() = transform(source.value)

    override val replayCache: List<Target> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Target>): Nothing =
        source.collect { value ->
            collector.emit(transform(value))
        }
}

private fun YPlayerState.toLegacyPlaybackState(): PlaybackState =
    PlaybackState(
        playing = playing,
        buffering = buffering,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        speed = speed,
        videoHeight = diagnostics.videoHeight,
        currentIndex = currentIndex,
        itemCount = itemCount,
        audioTracks = audioTracks.map(YTrack::toEngineTrack),
        subtitleTracks = subtitleTracks.map(YTrack::toEngineTrack),
        discNavigation = discNavigation,
        error = error,
        errorKind = errorCategory?.toLegacyFailureKind(),
        ended = phase == YPlaybackPhase.Ended,
        fallbacksExhausted = phase == YPlaybackPhase.Failed,
        automaticFallbackBlocked =
            errorCategory == YPlaybackFailureCategory.Authorization ||
                errorCategory == YPlaybackFailureCategory.Drm,
        diagnostics =
            PlaybackDiagnostics(
                engine = "YCore 2.0",
                decoder = diagnostics.decoder.ifBlank { "等待解码器" },
                videoCodec = diagnostics.videoCodec.ifBlank { "未知" },
                playMethod = diagnostics.route.name,
                videoWidth = diagnostics.videoWidth,
                dynamicRange = diagnostics.dynamicRange,
                audioFormat = diagnostics.audioCodec,
                videoOutput = diagnostics.videoOutput.ifBlank { "等待首帧" },
                audioOutput = diagnostics.audioOutput.ifBlank { "等待音频输出" },
                videoReadiness =
                    when {
                        diagnostics.videoOutputVerified -> PlaybackOutputReadiness.Rendering
                        phase == YPlaybackPhase.Idle -> PlaybackOutputReadiness.Released
                        else -> PlaybackOutputReadiness.Waiting
                    },
                audioReadiness =
                    when {
                        diagnostics.audioOutputVerified -> PlaybackOutputReadiness.Rendering
                        phase == YPlaybackPhase.Idle -> PlaybackOutputReadiness.Released
                        else -> PlaybackOutputReadiness.Waiting
                    },
                dolbyVisionOutput = diagnostics.dolbyVisionOutput,
                dolbyVisionRpuApplied = diagnostics.dolbyVisionRpuApplied,
                dolbyVisionEnhancementLayerComposed = diagnostics.dolbyVisionFelComposed,
                immersiveAudioCarrierOutput = diagnostics.immersiveAudioCarrierOutput,
                dolbyAtmosSourceDetected = diagnostics.dolbyAtmosSourceDetected,
                dolbyAtmosOutputMode = diagnostics.dolbyAtmosOutputMode,
                audioOutputRoute = diagnostics.audioOutputRoute,
                audioOutputRouteVerified = diagnostics.audioOutputRouteVerified,
                dolbyAtmosOutput = diagnostics.dolbyAtmosOutput,
                spatialAudioOutput = diagnostics.spatialAudioOutput,
                headTrackingAvailable = diagnostics.headTrackingAvailable,
                avSyncOffsetMs = diagnostics.avSyncOffsetMs,
                avSyncMeasurement = diagnostics.avSyncMeasurement,
                plannedRenderPath = diagnostics.renderer,
                planningReason = diagnostics.reason,
                mediaProbe = diagnostics.demuxer,
                bitrateBitsPerSecond = diagnostics.bitrateBitsPerSecond,
                frameRate = diagnostics.frameRate,
                droppedFrames = diagnostics.droppedFrames,
                sourceQueueBytes = diagnostics.sourceQueueBytes,
                sourceBufferedMs = diagnostics.sourceBufferedMs,
                sourceStarvationCount = diagnostics.sourceStarvationCount,
                networkBitsPerSecond = diagnostics.networkBitsPerSecond,
                outputEvidence = diagnostics.toPlaybackOutputEvidence(phase),
                outputEvidenceGeneration = diagnostics.outputEvidenceGeneration,
                outputEvidenceResetReason = diagnostics.outputEvidenceResetReason.name,
            ),
    )

internal fun com.yfuse.core2.api.YPlayerDiagnostics.toPlaybackOutputEvidence(
    phase: YPlaybackPhase,
): PlaybackOutputEvidence {
    val videoReadiness =
        when {
            videoOutputVerified -> PlaybackOutputReadiness.Rendering
            phase == YPlaybackPhase.Idle -> PlaybackOutputReadiness.Released
            else -> PlaybackOutputReadiness.Waiting
        }
    val audioReadiness =
        when {
            audioOutputVerified -> PlaybackOutputReadiness.Rendering
            phase == YPlaybackPhase.Idle -> PlaybackOutputReadiness.Released
            else -> PlaybackOutputReadiness.Waiting
        }
    val decoderParts = decoder.split(" + ", limit = 2)
    val videoTrackKnown =
        videoCodec.isNotBlank() || videoWidth > 0 || videoHeight > 0 || videoOutputVerified
    val audioTrackKnown = audioCodec.isNotBlank() || audioOutputVerified
    return PlaybackOutputEvidence(
        sessionRevision = if (phase == YPlaybackPhase.Idle) 0L else outputEvidenceGeneration.coerceAtLeast(1L),
        videoReadiness = videoReadiness,
        audioReadiness = audioReadiness,
        videoConfidence =
            if (videoOutputVerified) PlaybackEvidenceConfidence.Confirmed else PlaybackEvidenceConfidence.Requested,
        audioConfidence =
            if (audioOutputVerified) PlaybackEvidenceConfidence.Confirmed else PlaybackEvidenceConfidence.Requested,
        // The native label combines "video + audio" decoders. A single decoder on an
        // audio-only source must not become proof that the item contains video.
        videoDecoder = decoderParts.firstOrNull().orEmpty().takeIf { videoTrackKnown }.orEmpty(),
        audioDecoder =
            when {
                !audioTrackKnown -> ""
                decoderParts.size > 1 -> decoderParts[1]
                !videoTrackKnown -> decoderParts.firstOrNull().orEmpty()
                else -> ""
            },
        inputDynamicRange = dynamicRange,
        outputDynamicRange = dynamicRange.takeIf { videoOutputVerified }.orEmpty(),
        dynamicRangeOutputMode =
            if (dolbyVisionOutput) {
                PlaybackDynamicRangeOutputMode.DolbyVisionMediaCodec
            } else {
                PlaybackDynamicRangeOutputMode.Unknown
            },
        dolbyVisionRpuRendered = dolbyVisionRpuApplied,
        dolbyVisionFelComposed = dolbyVisionFelComposed,
        renderApi =
            when (route) {
                com.yfuse.core2.api.YPlaybackRoute.NativeDirect,
                com.yfuse.core2.api.YPlaybackRoute.NativeEnhanced,
                com.yfuse.core2.api.YPlaybackRoute.NativeTunnel,
                -> PlaybackVideoRenderApi.MediaCodecSurface
                com.yfuse.core2.api.YPlaybackRoute.GpuEnhanced -> PlaybackVideoRenderApi.Vulkan
                else -> PlaybackVideoRenderApi.Unknown
            },
        audioMode =
            when {
                !audioOutputVerified -> PlaybackAudioOutputMode.Unknown
                route == com.yfuse.core2.api.YPlaybackRoute.NativeTunnel -> PlaybackAudioOutputMode.Tunnel
                dolbyAtmosOutputMode.encodedPassthrough -> PlaybackAudioOutputMode.Passthrough
                else -> PlaybackAudioOutputMode.Pcm
            },
        tunneledPlayback = route == com.yfuse.core2.api.YPlaybackRoute.NativeTunnel,
        codecResetCount = codecResetCount,
        audioUnderrunCount = audioUnderrunCount,
        droppedFramesMeasured = droppedFramesMeasured,
        avSyncMeasured = avSyncMeasured,
        rendererDetail = renderer,
    )
}

private fun YTrack.toEngineTrack(): EngineTrack =
    EngineTrack(
        id = id,
        label = label,
        language = language,
        selected = selected,
        codec = codec,
    )

private fun YPlaybackFailureCategory.toLegacyFailureKind(): PlaybackFailureKind =
    when (this) {
        YPlaybackFailureCategory.Authorization -> PlaybackFailureKind.Authorization
        YPlaybackFailureCategory.Drm -> PlaybackFailureKind.Drm
        YPlaybackFailureCategory.Network -> PlaybackFailureKind.Network
        YPlaybackFailureCategory.Container -> PlaybackFailureKind.Container
        YPlaybackFailureCategory.Decoder -> PlaybackFailureKind.Decoder
        YPlaybackFailureCategory.Renderer -> PlaybackFailureKind.Renderer
        YPlaybackFailureCategory.AudioSink -> PlaybackFailureKind.AudioSink
        YPlaybackFailureCategory.Unknown -> PlaybackFailureKind.Unknown
    }
