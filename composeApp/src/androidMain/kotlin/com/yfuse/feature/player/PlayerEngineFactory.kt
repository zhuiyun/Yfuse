package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yfuse.BuildConfig
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.NativePlaybackComponent
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDolbyVisionRuntimeCapabilities
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.cachedLocalPlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind
import com.yfuse.core2.android.AndroidCore2TrialFactory
import com.yfuse.core2.android.core2NativeBaselineBlockReason
import kotlinx.coroutines.CoroutineScope

internal fun shouldUseCore2Trial(
    enabled: Boolean,
    engineSelection: PlaybackEngineSelection,
    crashBlocked: Boolean,
): Boolean =
    enabled &&
        engineSelection == PlaybackEngineSelection.Auto &&
        !crashBlocked

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
    scope: CoroutineScope,
    stopEncoding: suspend (String) -> Boolean,
    core2TrialEnabled: Boolean = false,
    core2NativeOnlyEnabled: Boolean = false,
    engineSelection: PlaybackEngineSelection = PlaybackEngineSelection.Auto,
    allowAudioPassthrough: Boolean = false,
    frameRateMatch: PlaybackFrameRateMatch = PlaybackFrameRateMatch.Disabled,
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities =
        PlaybackDolbyVisionRuntimeCapabilities.conservative(),
    capabilitySignature: String = "unknown",
): VideoEngine {
    val packagedNativeOnly = BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME
    val resolvedNativeOnly = packagedNativeOnly || core2NativeOnlyEnabled
    val resolvedDecoderMode =
        AndroidNativeCrashMonitor.safeDecoderMode(kind, decoderMode, capabilitySignature)
    val yCoreAllowed =
        packagedNativeOnly ||
            shouldUseCore2Trial(
                enabled = core2TrialEnabled,
                engineSelection = engineSelection,
                crashBlocked =
                    AndroidNativeCrashMonitor.isYCoreDemuxBlocked(
                        decoderMode,
                        capabilitySignature,
                    ),
            )
    val component =
        if (yCoreAllowed) {
            NativePlaybackComponent.YCoreDemux
        } else {
            when (kind) {
                PlayerEngine.Mpv -> NativePlaybackComponent.Mpv
                PlayerEngine.Mdk -> NativePlaybackComponent.Mdk
                PlayerEngine.Exo -> NativePlaybackComponent.Unknown
            }
        }
    AndroidNativeCrashMonitor.arm(
        component = component,
        engine = kind,
        decoderMode = resolvedDecoderMode,
        capabilitySignature = capabilitySignature,
        media = items.getOrNull(startIndex),
    )
    if (yCoreAllowed) {
        if (resolvedNativeOnly) {
            items.core2NativeBaselineBlockReason(startIndex)?.let { reason ->
                return MissingNativeCapabilityVideoEngine(
                    message = reason,
                    startIndex = startIndex,
                    itemCount = items.size,
                    startPositionMs = startPositionMs,
                )
            }
        }
        AndroidCore2TrialFactory
            .create(
                context = context,
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                startPlaybackRequested = startPlaybackRequested,
                startSpeed = startSpeed,
                autoNext = autoNext,
                customUserAgent = customUserAgent,
                allowAudioPassthrough = allowAudioPassthrough,
                frameRateMatch = frameRateMatch,
                videoCacheBytes = videoCacheBytes,
                nativeOnly = resolvedNativeOnly,
            )?.let { return it }
        if (resolvedNativeOnly) {
            return MissingNativeCapabilityVideoEngine(
                message = "YCore Native 当前无法建立纯内核播放路径",
                startIndex = startIndex,
                itemCount = items.size,
                startPositionMs = startPositionMs,
            )
        }
    }

    return when (kind) {
        PlayerEngine.Mdk ->
            if (BuildConfig.YFUSE_MDK_INCLUDED) {
                MdkVideoEngine(
                    items = items,
                    context = context,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    startPlaybackRequested = startPlaybackRequested,
                    startSpeed = startSpeed,
                    decoderMode = resolvedDecoderMode,
                    autoNext = autoNext,
                    customUserAgent = customUserAgent,
                    scope = scope,
                    stopEncoding = stopEncoding,
                    videoCacheBytes = videoCacheBytes,
                )
            } else {
                ExoVideoEngine(
                    context = context,
                    items = items,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                    startPlaybackRequested = startPlaybackRequested,
                    startSpeed = startSpeed,
                    scope = scope,
                    decoderMode = resolvedDecoderMode,
                    optimizationMode = optimizationMode,
                    autoNext = autoNext,
                    customUserAgent = customUserAgent,
                    videoCacheBytes = videoCacheBytes,
                    stopEncoding = stopEncoding,
                )
            }

        PlayerEngine.Mpv -> {
            val missingDiscCapability = missingNativeBluRayCapability(items, startIndex)
            if (missingDiscCapability != null) {
                MissingNativeCapabilityVideoEngine(
                    message = missingDiscCapability,
                    startIndex = startIndex,
                    itemCount = items.size,
                    startPositionMs = startPositionMs,
                )
            } else {
                MpvVideoEngine(
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
                    scope = scope,
                    stopEncoding = stopEncoding,
                    dolbyVisionRuntime = dolbyVisionRuntime,
                    videoCacheBytes = videoCacheBytes,
                )
            }
        }

        else ->
            ExoVideoEngine(
                context = context,
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                startPlaybackRequested = startPlaybackRequested,
                startSpeed = startSpeed,
                scope = scope,
                decoderMode = resolvedDecoderMode,
                optimizationMode = optimizationMode,
                autoNext = autoNext,
                customUserAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
                stopEncoding = stopEncoding,
            )
    }
}

/**
 * Fails fast only when the selected source provably needs a feature the installed native AAR lacks.
 *
 * Generic ISO remains ungated until the bounded image inspector classifies it: a DVD image must not
 * be rejected merely because the Blu-ray bridge is absent. File-system BDMV can still use mpv's
 * native path when libbluray is present, while a SAF `content://` BDMV tree specifically requires
 * Yfuse's `bd_open_files()` VFS and a seekable `content://` ISO requires the block/JNI bridge.
 */
internal fun missingNativeBluRayCapability(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    nativeCapabilities: MpvNativeBuildCapabilities = installedMpvNativeBuildCapabilities,
): String? {
    val item = items.getOrNull(startIndex) ?: return null
    val url = item.url
    if (
        !url.startsWith("file://", ignoreCase = true) &&
        !url.startsWith("content://", ignoreCase = true)
    ) {
        return null
    }
    val version = item.activeVersion
    val declared =
        detectPlaybackDiscKind(
            container = version?.container,
            labelHint = version?.label,
            declaredDiscSource = version?.discSource == true,
        )
    val kind = cachedLocalPlaybackDiscKind(url) ?: declared
    if (kind != PlaybackDiscKind.BluRay && kind != PlaybackDiscKind.Bdmv) return null

    if (!nativeCapabilities.nativeBluRay) {
        return "当前 libmpv AAR 未包含 libbluray，无法直读本地 ${kind.label}；请安装 Yfuse Blu-ray native AAR"
    }
    if (url.startsWith("content://", ignoreCase = true)) {
        if (kind == PlaybackDiscKind.Bdmv && !nativeCapabilities.bdmvVfs) {
            return "当前 native AAR 未包含 BDMV VFS，无法从 Android 文件树直读 BDMV；请安装完整 Yfuse Blu-ray AAR"
        }
        if (kind == PlaybackDiscKind.BluRay && !nativeCapabilities.remoteRawBluRay) {
            return "当前 native AAR 未包含 ISO 随机块桥接，无法从 content URI 直读 Blu-ray ISO；请安装完整 Yfuse Blu-ray AAR"
        }
    }
    return null
}
