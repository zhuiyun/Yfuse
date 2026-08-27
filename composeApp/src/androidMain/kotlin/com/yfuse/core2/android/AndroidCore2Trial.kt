package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind
import com.yfuse.core2.api.YDiscKind
import com.yfuse.core2.api.YDiscMedia
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.legacy.AndroidMpvCore2FallbackFactory
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.release.Core2NativeBaselineBlock
import com.yfuse.core2.release.Core2NativeBaselineSource
import com.yfuse.core2.release.evaluateCore2NativeBaseline
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.feature.player.AndroidPlaybackHttpProxy
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.VideoEngine
import com.yfuse.feature.player.externalSubtitleFormatHint
import com.yfuse.feature.player.persistentPlaybackCacheUrl
import com.yfuse.feature.player.startsWithServerTranscode

/**
 * Separate construction boundary for the production Core2 route with a user-controlled rollback.
 *
 * The switch lives in PlaybackPreferences instead of the legacy PlayerEngine enum, so users can
 * explicitly return to the already-selected Legacy engine without changing its preference.
 */
internal object AndroidCore2TrialFactory {
    fun create(
        context: Context,
        items: List<PlayerMediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        startPlaybackRequested: Boolean,
        startSpeed: Float,
        autoNext: Boolean,
        customUserAgent: String,
        allowAudioPassthrough: Boolean,
        frameRateMatch: PlaybackFrameRateMatch,
        videoCacheBytes: Long = 0L,
        nativeOnly: Boolean = false,
    ): VideoEngine? {
        if (!items.canUseCore2Trial(startIndex)) return null
        val cacheProxy =
            if (videoCacheBytes > 0L) {
                runCatching {
                    AndroidPlaybackHttpProxy(
                        context = context.applicationContext,
                        userAgent = customUserAgent,
                        videoCacheBytes = videoCacheBytes,
                    )
                }.getOrNull()
            } else {
                null
            }
        val request =
            YPlayerOpenRequest(
                items =
                    items.toCore2MediaItems(customUserAgent) { item, upstreamUrl ->
                        val cacheable =
                            item.persistentPlaybackCacheUrl(item.startsWithServerTranscode()) ==
                                upstreamUrl.trim()
                        if (cacheable) {
                            cacheProxy?.localUrl(upstreamUrl, cacheable = true) ?: upstreamUrl
                        } else {
                            upstreamUrl
                        }
                    },
                startIndex = startIndex,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = startPlaybackRequested,
                autoNext = autoNext,
            )
        val compatibilityFactory =
            if (nativeOnly) {
                null
            } else {
                AndroidMpvCore2FallbackFactory(
                    context = context,
                    sourceItems = items.associateBy(PlayerMediaItem::id),
                )
            }
        return try {
            val player =
                AndroidAdaptiveCore2YPlayer(
                    context = context.applicationContext,
                    request = request,
                    fallbackRouteFactory = compatibilityFactory,
                    discRouteFactory = compatibilityFactory,
                    allowAudioPassthrough = allowAudioPassthrough,
                    frameRateSwitchMode = frameRateMatch.toCore2Mode(),
                    onRelease = { cacheProxy?.close() },
                )
            player.setSpeed(startSpeed)
            player.prepare()
            YPlayerVideoEngineAdapter(player)
        } catch (error: Throwable) {
            cacheProxy?.close()
            throw error
        }
    }
}

private fun PlaybackFrameRateMatch.toCore2Mode(): YFrameRateSwitchMode =
    when (this) {
        PlaybackFrameRateMatch.Disabled -> YFrameRateSwitchMode.Disabled
        PlaybackFrameRateMatch.SeamlessOnly -> YFrameRateSwitchMode.SeamlessOnly
        PlaybackFrameRateMatch.Always -> YFrameRateSwitchMode.Always
    }

internal fun List<PlayerMediaItem>.canUseCore2Trial(startIndex: Int): Boolean {
    if (isEmpty() || startIndex !in indices) return false
    return all { item ->
        val version = item.activeVersion
        // Core2's current private-surface/runtime probe is not yet a safe proof for Dolby-only
        // streams (notably DV profile 5). Vendor Dolby decoders can reject that probe even though
        // Exo + the real display Surface works correctly. Unknown DV profiles are treated the same
        // way until the bitstream probe has enough evidence to prove a compatible base layer.
        val requiresProvenDolbyPipeline =
            version?.dolbyVision == true &&
                (version.dolbyProfile == null || version.needsDolbyDecoder)
        !requiresProvenDolbyPipeline &&
            item.drmConfiguration == null &&
            version?.drmConfiguration == null &&
            item.externalSubtitleUri.isCore2SubtitleSourceSupported() &&
            item.url.substringBefore(':').lowercase() in CORE2_SOURCE_SCHEMES
    }
}

internal fun List<PlayerMediaItem>.core2NativeBaselineBlockReason(startIndex: Int): String? {
    val item = getOrNull(startIndex) ?: return "YCore Native 缺少当前播放项"
    val version = item.activeVersion
    val source =
        Core2NativeBaselineSource(
            hasMetadata = version != null,
            scheme = item.url.substringBefore(':').lowercase(),
            container = version?.container,
            videoCodec = version?.sourceVideoCodec,
            serverTranscode = item.startsWithServerTranscode(),
            adaptiveManifest = item.url.isAdaptiveManifest(),
            disc = version?.discSource == true,
            drm = item.drmConfiguration != null || version?.drmConfiguration != null,
            dolbyVision = version?.dolbyVision == true,
            externalSubtitleSupported = item.externalSubtitleUri.isCore2SubtitleSourceSupported(),
        )
    return evaluateCore2NativeBaseline(source)?.userMessage()
}

private fun Core2NativeBaselineBlock.userMessage(): String =
    when (this) {
        Core2NativeBaselineBlock.MissingMetadata -> "YCore Native 缺少片源格式元数据"
        Core2NativeBaselineBlock.UnsupportedScheme -> "YCore Native 暂不支持当前来源协议"
        Core2NativeBaselineBlock.ServerTranscode -> "YCore Native 基线仅验证直连片源"
        Core2NativeBaselineBlock.AdaptiveManifest -> "YCore Native 尚未完成 HLS/DASH"
        Core2NativeBaselineBlock.UnsupportedContainer -> "YCore Native 当前仅验证 MP4/MKV"
        Core2NativeBaselineBlock.UnsupportedVideoCodec ->
            "YCore Native 当前仅验证 H.264/HEVC"
        Core2NativeBaselineBlock.Disc -> "YCore Native 尚未完成原盘导航"
        Core2NativeBaselineBlock.Drm -> "YCore Native 尚未完成 DRM"
        Core2NativeBaselineBlock.DolbyVision -> "YCore Native 尚未完成杜比视界渲染"
        Core2NativeBaselineBlock.ExternalSubtitle -> "YCore Native 不支持当前外挂字幕来源"
    }

private fun String.isAdaptiveManifest(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

internal fun List<PlayerMediaItem>.toCore2MediaItems(
    customUserAgent: String,
    localize: (PlayerMediaItem, String) -> String = { _, uri -> uri },
): List<YMediaItem> {
    val headers =
        customUserAgent
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { mapOf(USER_AGENT_HEADER to it) }
            .orEmpty()
    return map { item -> item.toCore2MediaItem(headers, localize) }
}

private fun PlayerMediaItem.toCore2MediaItem(
    headers: Map<String, String>,
    localize: (PlayerMediaItem, String) -> String,
): YMediaItem {
    val usingServerTranscode = startsWithServerTranscode()
    val version = activeVersion
    return YMediaItem(
        id = id,
        uri =
            localize(
                this,
                if (usingServerTranscode) {
                    transcodeUrl.ifBlank { fallbackTranscodeUrl }
                } else {
                    url
                },
            ),
        title = title,
        headers = headers,
        providerKey = serverId,
        externalSubtitle =
            externalSubtitleUri
                ?.takeIf(String::isNotBlank)
                ?.let { uri ->
                    YExternalSubtitleSource(
                        uri = uri,
                        language = externalSubtitleLanguage,
                    )
                },
        disc =
            if (!usingServerTranscode && version?.discSource == true) {
                YDiscMedia(
                    kind =
                        detectPlaybackDiscKind(
                            container = version.container,
                            labelHint = version.label,
                            declaredDiscSource = true,
                        ).toCore2DiscKind(),
                    container = version.container,
                    label = version.label,
                )
            } else {
                null
            },
    )
}

private fun String?.isCore2SubtitleSourceSupported(): Boolean {
    if (isNullOrBlank()) return true
    if (substringBefore(':').lowercase() !in CORE2_SUBTITLE_SOURCE_SCHEMES) return false
    return externalSubtitleFormatHint(this)?.let(CORE2_SUBTITLE_FORMATS::contains) != false
}

private fun PlaybackDiscKind.toCore2DiscKind(): YDiscKind =
    when (this) {
        PlaybackDiscKind.Iso -> YDiscKind.Iso
        PlaybackDiscKind.Dvd -> YDiscKind.Dvd
        PlaybackDiscKind.BluRay -> YDiscKind.BluRay
        PlaybackDiscKind.Bdmv -> YDiscKind.Bdmv
        PlaybackDiscKind.None,
        PlaybackDiscKind.Unknown,
        -> YDiscKind.Unknown
    }

private val CORE2_SOURCE_SCHEMES =
    setOf(
        "http",
        "https",
        "smb",
        "webdav",
        "webdavs",
        "file",
        "content",
        "android.resource",
        "yfusebd",
        "yfusebdmv",
    )
private val CORE2_SUBTITLE_SOURCE_SCHEMES =
    setOf(
        "http",
        "https",
        "file",
        "content",
        "android.resource",
    )
private val CORE2_SUBTITLE_FORMATS = setOf("srt", "vtt", "webvtt", "ass", "ssa")
private const val USER_AGENT_HEADER = "User-Agent"
