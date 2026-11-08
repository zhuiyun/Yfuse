package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.data.SenPlayerPictureSettings
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.NativePlaybackComponent
import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core2.android.AndroidCore2TrialFactory
import com.yfuse.core2.android.core2NativeBaselineBlockReason
import com.yfuse.feature.filesource.isFileSourcePlayback
import kotlinx.coroutines.flow.StateFlow

/** A selected SenPlayer session must not silently become a different engine for a file transport. */
internal fun playbackEngineUnsupportedReason(
    kind: PlayerEngine,
    items: List<PlayerMediaItem>,
): String? =
    if (kind == PlayerEngine.SenPlayer && items.any { it.requiresYCoreFileTransport() }) {
        "SenPlayer 不支持此队列所需的文件源凭据或传输协议，请手动选择 YCore"
    } else {
        null
    }

/** Android engine construction boundary; callers depend only on [VideoEngine]. */
@OptIn(UnstableApi::class)
internal fun createVideoEngine(
    kind: PlayerEngine,
    context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    startSpeed: Float,
    decoderMode: DecoderMode,
    optimizationMode: PlaybackOptimizationMode,
    autoNext: Boolean,
    customUserAgent: String,
    videoCacheBytes: Long,
    senPlayerPictureSettings: StateFlow<SenPlayerPictureSettings>? = null,
    yCoreBufferTargetUs: Long? = null,
    allowAudioPassthrough: Boolean = false,
    frameRateMatch: PlaybackFrameRateMatch = PlaybackFrameRateMatch.Disabled,
    deviceCapabilities: PlaybackDeviceCapabilities = PlaybackDeviceCapabilities.conservative(),
    capabilitySignature: String = "unknown",
    initialTrackSelections: Map<String, com.yfuse.core2.api.YInitialTrackSelection> = emptyMap(),
    crashOwner: String =
        java.util.UUID
            .randomUUID()
            .toString(),
): VideoEngine {
    fun unavailable(message: String): VideoEngine =
        MissingNativeCapabilityVideoEngine(
            message = message,
            startIndex = startIndex,
            itemCount = items.size,
            startPositionMs = startPositionMs,
        )
    playbackEngineUnsupportedReason(kind, items)?.let { return unavailable(it) }
    val resolvedDecoderMode =
        AndroidNativeCrashMonitor.safeDecoderMode(kind, decoderMode, capabilitySignature)
    AndroidNativeCrashMonitor.arm(
        owner = crashOwner,
        component =
            if (kind ==
                PlayerEngine.YCore
            ) {
                NativePlaybackComponent.YCoreDemux
            } else {
                NativePlaybackComponent.SenPlayer
            },
        engine = kind,
        decoderMode = resolvedDecoderMode,
        capabilitySignature = capabilitySignature,
        media = items.getOrNull(startIndex),
    )
    // SenPlayer is an explicit choice. The no-legacy-fallback packaging flag must never mask it.
    if (kind == PlayerEngine.SenPlayer) {
        return SenPlayerVideoEngine(
            context = context,
            items = items,
            startIndex = startIndex,
            startPositionMs = startPositionMs,
            startPlaybackRequested = startPlaybackRequested,
            startSpeed = startSpeed,
            decoderMode = resolvedDecoderMode,
            autoNext = autoNext,
            customUserAgent = customUserAgent,
            videoCacheBytes = videoCacheBytes,
            initialTrackSelections = initialTrackSelections,
            pictureSettings = senPlayerPictureSettings,
        )
    }

    if (PlaybackRemotePolicyRegistry.isDisabled(PlaybackRemotePath.YCoreAll) ||
        PlaybackRemotePolicyRegistry.isDisabled(PlaybackRemotePath.YCoreDemux)
    ) {
        return unavailable("YCore 当前被远程策略临时停用")
    }
    if (AndroidNativeCrashMonitor.isYCoreDemuxBlocked(decoderMode, capabilitySignature)) {
        return unavailable("YCore 当前解封装与解码器组合已因连续 native 崩溃被隔离")
    }
    items.core2NativeBaselineBlockReason(startIndex)?.let { return unavailable(it) }
    val gpuBlocked =
        AndroidNativeCrashMonitor.isYCoreGpuBlocked(decoderMode, capabilitySignature) ||
            PlaybackRemotePolicyRegistry.isDisabled(PlaybackRemotePath.YCoreGpu)
    return AndroidCore2TrialFactory.create(
        context = context,
        items = items,
        startIndex = startIndex,
        startPositionMs = startPositionMs,
        startPlaybackRequested = startPlaybackRequested,
        startSpeed = startSpeed,
        decoderMode = resolvedDecoderMode,
        optimizationMode = optimizationMode,
        autoNext = autoNext,
        customUserAgent = customUserAgent,
        allowAudioPassthrough = allowAudioPassthrough,
        allowDolbyVisionHls = deviceCapabilities.supportsDolbyVisionOutput,
        allowDolbyAtmosHls =
            allowAudioPassthrough && PlaybackAudioCodec.Eac3Joc in deviceCapabilities.directAudioFormats,
        frameRateMatch = frameRateMatch,
        videoCacheBytes = videoCacheBytes,
        yCoreBufferTargetUs = yCoreBufferTargetUs,
        allowNativeGpu = !gpuBlocked,
        initialTrackSelections = initialTrackSelections,
    ) ?: unavailable("YCore 当前无法建立播放路径")
}

internal fun PlayerMediaItem.requiresYCoreFileTransport(): Boolean =
    isFileSourcePlayback &&
        (
            transportCredentials != null ||
                activeVersion?.discSource == true ||
                !(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
        )
