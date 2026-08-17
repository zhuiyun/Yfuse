package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.cachedLocalPlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind
import kotlinx.coroutines.CoroutineScope

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
    quality: PlaybackQuality,
    customUserAgent: String,
    videoCacheBytes: Long,
    scope: CoroutineScope,
    stopEncoding: suspend (String) -> Boolean,
): VideoEngine =
    when (kind) {
        PlayerEngine.Mdk ->
            MdkVideoEngine(
                items = items,
                startIndex = startIndex,
                startPositionMs = startPositionMs,
                startPlaybackRequested = startPlaybackRequested,
                startSpeed = startSpeed,
                decoderMode = decoderMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                scope = scope,
                stopEncoding = stopEncoding,
            )

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
                    decoderMode = decoderMode,
                    autoNext = autoNext,
                    quality = quality,
                    customUserAgent = customUserAgent,
                    scope = scope,
                    stopEncoding = stopEncoding,
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
                decoderMode = decoderMode,
                optimizationMode = optimizationMode,
                autoNext = autoNext,
                quality = quality,
                customUserAgent = customUserAgent,
                videoCacheBytes = videoCacheBytes,
                stopEncoding = stopEncoding,
            )
    }

/**
 * Returns a user-facing failure only when the active local source is known to be Blu-ray.
 *
 * Generic ISO remains ungated until the bounded image inspector has classified it: a DVD image must
 * not be rejected merely because the mpv build lacks libbluray. Once classification is cached as
 * Blu-ray/BDMV, the concrete AAR marker is authoritative.
 */
private fun missingNativeBluRayCapability(
    items: List<PlayerMediaItem>,
    startIndex: Int,
): String? {
    if (installedMpvNativeBuildCapabilities.nativeBluRay) return null
    val item = items.getOrNull(startIndex) ?: return null
    val url = item.url
    if (!url.startsWith("file://", ignoreCase = true)) return null
    val version = item.activeVersion
    val declared =
        detectPlaybackDiscKind(
            container = version?.container,
            labelHint = version?.label,
            declaredDiscSource = version?.discSource == true,
        )
    val kind = cachedLocalPlaybackDiscKind(url) ?: declared
    if (kind != PlaybackDiscKind.BluRay && kind != PlaybackDiscKind.Bdmv) return null
    return "当前 libmpv AAR 未包含 libbluray，无法直读本地 ${kind.label}；请安装 Yfuse Blu-ray native AAR"
}
