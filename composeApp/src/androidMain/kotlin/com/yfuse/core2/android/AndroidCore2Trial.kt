package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.network.embyPlaybackHeaders
import com.yfuse.core.platform.AppBuildConfig
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.detectPlaybackDiscKind
import com.yfuse.core2.api.YDiscKind
import com.yfuse.core2.api.YDiscMedia
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.yMediaSourceKey
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.release.Core2NativeBaselineBlock
import com.yfuse.core2.release.Core2NativeBaselineSource
import com.yfuse.core2.release.evaluateCore2NativeBaseline
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.strategy.YDecoderPreference
import com.yfuse.core2.strategy.YOptimizationPreference
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.feature.filesource.isFileSourcePlayback
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.VideoEngine
import com.yfuse.feature.player.allowsServerTranscodeFallback
import com.yfuse.feature.player.externalSubtitleFormatHint
import com.yfuse.feature.player.persistentPlaybackCacheUrl
import com.yfuse.feature.player.playbackExternalSubtitles
import com.yfuse.feature.player.startsWithServerTranscode
import kotlinx.coroutines.CancellationException

/**
 * Construction boundary for YCore's independently executable Android playback routes.
 * Every child owns its platform or YCore native decode path; there is no external engine fallback.
 */
internal object AndroidCore2TrialFactory {
    fun create(
        context: Context,
        items: List<PlayerMediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        startPlaybackRequested: Boolean,
        startSpeed: Float,
        decoderMode: DecoderMode = DecoderMode.Auto,
        optimizationMode: PlaybackOptimizationMode = PlaybackOptimizationMode.Balanced,
        autoNext: Boolean,
        customUserAgent: String,
        allowAudioPassthrough: Boolean,
        allowDolbyVisionHls: Boolean,
        allowDolbyAtmosHls: Boolean,
        frameRateMatch: PlaybackFrameRateMatch,
        videoCacheBytes: Long = 0L,
        yCoreBufferTargetUs: Long? = null,
        allowNativeGpu: Boolean = true,
        initialTrackSelections: Map<String, com.yfuse.core2.api.YInitialTrackSelection> = emptyMap(),
    ): VideoEngine? {
        if (!items.canUseCore2Trial(startIndex)) return null
        val yCoreProxy =
            if (items.any { item -> item.requiresYCoreAdaptiveProxy() }) {
                runCatching {
                    AndroidYCoreHttpProxy(
                        context = context.applicationContext,
                        userAgent = customUserAgent,
                        cacheMaximumBytes = videoCacheBytes.coerceAtLeast(0L),
                    )
                }.getOrNull() ?: return null
            } else {
                null
            }
        val request =
            YPlayerOpenRequest(
                items =
                    items.toCore2MediaItems(
                        customUserAgent = customUserAgent,
                        cacheMaximumBytes = videoCacheBytes,
                        initialTrackSelections = initialTrackSelections,
                        localize = { item, upstreamUrl ->
                            // Static MP4/MKV/ISO sources go to YCore as they are: its protocol-aware
                            // MediaDataSource reads them through its own range transport and block
                            // cache. A loopback proxy in front would nest a second cache, repeat the
                            // upstream size probe for every block, and hand the queue an address no
                            // later queue update matches. Only authored adaptive manifests, which
                            // need URI rewriting, use YCore's manifest proxy.
                            if (item.requiresYCoreAdaptiveProxy(upstreamUrl)) {
                                val usingServerTranscode = item.usesApprovedCore2ServerTranscode()
                                val sourceVersion = item.activeVersion.takeUnless { usingServerTranscode }
                                val cacheable =
                                    !usingServerTranscode &&
                                        item.persistentPlaybackCacheUrl(false) == upstreamUrl.trim()
                                requireNotNull(yCoreProxy).localUrl(
                                    upstreamUri = upstreamUrl,
                                    upstreamHeaders =
                                        customUserAgent
                                            .trim()
                                            .takeIf(String::isNotEmpty)
                                            ?.let { mapOf(USER_AGENT_HEADER to it) }
                                            .orEmpty() +
                                            embyPlaybackHeaders(upstreamUrl) { AppBuildConfig.VERSION_NAME } +
                                            item.core2CustomHeaders(upstreamUrl),
                                    credentials = item.transportCredentials.takeUnless { usingServerTranscode },
                                    originBoundHeaderNames = item.core2CustomHeaders(upstreamUrl).keys,
                                    cacheable = cacheable,
                                    cacheIdentity = item.yCoreCacheIdentity().takeUnless { usingServerTranscode },
                                    maximumWidth = sourceVersion?.sourceWidth,
                                    maximumHeight = sourceVersion?.sourceHeight,
                                    hlsManifest =
                                        upstreamUrl.isHlsManifest() ||
                                            sourceVersion?.container.isHlsContainer(),
                                    dashManifest =
                                        upstreamUrl.isDashManifest() ||
                                            sourceVersion?.container.isDashContainer(),
                                    drmProtected =
                                        item.drmConfiguration != null ||
                                            item.activeVersion?.drmConfiguration != null,
                                    // The authored HLS master is the source of truth. Emby metadata
                                    // can omit rendition-level Dolby facts that AVFoundation sees.
                                    allowDolbyVisionHls = allowDolbyVisionHls,
                                    allowDolbyAtmosHls = allowDolbyAtmosHls,
                                )
                            } else {
                                upstreamUrl
                            }
                        },
                    ),
                startIndex = startIndex,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = startPlaybackRequested,
                autoNext = autoNext,
            )
        val frameRateSwitchMode = frameRateMatch.toCore2Mode()
        val nativeGpuRuntimeProbe =
            if (allowNativeGpu) {
                AndroidYCoreGpuRuntime.probe(context.applicationContext)
            } else {
                AndroidYCoreGpuRuntime.disabledProbe()
            }
        val routeEvaluator =
            AndroidCore2RouteEvaluator(
                context = context.applicationContext,
                decoderPreference = decoderMode.toCore2Preference(),
                optimizationPreference = optimizationMode.toCore2Preference(),
                nativeGpuRuntimeProbe = nativeGpuRuntimeProbe,
            )
        val discFactory =
            AndroidYCoreDiscRouteFactory(
                context = context,
                allowAudioPassthrough = allowAudioPassthrough,
                frameRateSwitchMode = frameRateSwitchMode,
            )
        return try {
            val player =
                AndroidAdaptiveCore2YPlayer(
                    context = context.applicationContext,
                    request = request,
                    routeEvaluator = routeEvaluator,
                    discRouteFactory = discFactory,
                    allowAudioPassthrough = allowAudioPassthrough,
                    frameRateSwitchMode = frameRateSwitchMode,
                    nativeGpuRuntimeProbe = nativeGpuRuntimeProbe,
                    preferSoftwareDecode = decoderMode == DecoderMode.Software,
                    preferredRemoteBufferTargetUs = yCoreBufferTargetUs,
                    adaptiveFeedbackSink = yCoreProxy,
                    onRelease = { yCoreProxy?.close() },
                )
            player.setSpeed(startSpeed)
            player.prepare()
            YPlayerVideoEngineAdapter(player)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            yCoreProxy?.close()
            throw error
        }
    }
}

private fun PlayerMediaItem.requiresYCoreAdaptiveProxy(): Boolean = requiresYCoreAdaptiveProxy(core2UpstreamUri())

internal fun PlayerMediaItem.requiresYCoreAdaptiveProxy(upstreamUrl: String): Boolean {
    val sourceVersion = activeVersion.takeUnless { usesApprovedCore2ServerTranscode() }
    return upstreamUrl.isHlsManifest() ||
        sourceVersion?.container.isHlsContainer() ||
        upstreamUrl.isDashManifest() ||
        sourceVersion?.container.isDashContainer()
}

private fun PlayerMediaItem.usesApprovedCore2ServerTranscode(): Boolean =
    startsWithServerTranscode() && allowsServerTranscodeFallback(forcedTranscodeByViewer)

private fun PlayerMediaItem.core2CustomHeaders(upstreamUri: String): Map<String, String> =
    customHeaders
        ?.values
        .orEmpty()
        .takeIf { mediaCredentialOriginsMatch(url, upstreamUri) }
        .orEmpty()

private fun PlayerMediaItem.core2UpstreamUri(): String =
    when {
        usesApprovedCore2ServerTranscode() -> transcodeUrl
        activeVersion?.discSource == true -> rawDiscUri?.takeIf(String::isNotBlank) ?: url
        else -> url
    }

private fun PlaybackFrameRateMatch.toCore2Mode(): YFrameRateSwitchMode =
    when (this) {
        PlaybackFrameRateMatch.Disabled -> YFrameRateSwitchMode.Disabled
        PlaybackFrameRateMatch.SeamlessOnly -> YFrameRateSwitchMode.SeamlessOnly
        PlaybackFrameRateMatch.Always -> YFrameRateSwitchMode.Always
    }

private fun DecoderMode.toCore2Preference(): YDecoderPreference =
    when (this) {
        DecoderMode.Hardware -> YDecoderPreference.HardwarePreferred
        DecoderMode.Software -> YDecoderPreference.Software
        DecoderMode.Auto -> YDecoderPreference.Automatic
    }

private fun PlaybackOptimizationMode.toCore2Preference(): YOptimizationPreference =
    when (this) {
        PlaybackOptimizationMode.Balanced -> YOptimizationPreference.Balanced
        PlaybackOptimizationMode.PowerSaver -> YOptimizationPreference.PowerSaver
        PlaybackOptimizationMode.Quality -> YOptimizationPreference.Quality
        PlaybackOptimizationMode.Compatibility -> YOptimizationPreference.Compatibility
    }

/**
 * Whether the item at [startIndex] can open in YCore. Only that item decides: one episode with a
 * Dolby Vision profile or DRM YCore cannot take used to keep the whole season off YCore, and in
 * earlier builds that left nothing to play at all. A later item YCore cannot take fails
 * when it is reached and reports that episode's unsupported capability explicitly.
 */
internal fun List<PlayerMediaItem>.canUseCore2Trial(startIndex: Int): Boolean {
    val item = getOrNull(startIndex) ?: return false
    if (item.startsWithServerTranscode() && !item.usesApprovedCore2ServerTranscode()) return false
    val version = item.activeVersion.takeUnless { item.usesApprovedCore2ServerTranscode() }
    // Unknown server metadata is not evidence that the stream is unsupported. Let it reach
    // YCore's local MediaExtractor/FFmpeg truth probe, then keep routing fail-closed if that
    // probe still cannot identify a supported Dolby Vision profile.
    val knownUnsupportedDolbyProfile =
        version?.dolbyVision == true &&
            version.dolbyProfile != null &&
            version.dolbyProfile !in CORE2_DOLBY_TRIAL_PROFILES
    val drmConfiguration = item.drmConfiguration ?: version?.drmConfiguration
    // Sidecars are loaded on selection by AndroidExternalSubtitleSession. A bad or
    // unsupported track must not veto this video's route.
    return !knownUnsupportedDolbyProfile &&
        (drmConfiguration == null || item.supportsCore2Drm(drmConfiguration.scheme)) &&
        item.core2UpstreamUri().substringBefore(':').lowercase() in CORE2_SOURCE_SCHEMES
}

internal fun List<PlayerMediaItem>.core2NativeBaselineBlockReason(startIndex: Int): String? {
    val item = getOrNull(startIndex) ?: return "YCore Native 缺少当前播放项"
    val usingServerTranscode = item.usesApprovedCore2ServerTranscode()
    val version = item.activeVersion.takeUnless { usingServerTranscode }
    val upstreamUri = item.core2UpstreamUri()
    val hlsManifest = upstreamUri.isHlsManifest() || version?.container.isHlsContainer()
    val dashManifest = upstreamUri.isDashManifest() || version?.container.isDashContainer()
    val drmConfiguration = item.drmConfiguration ?: item.activeVersion?.drmConfiguration
    val source =
        Core2NativeBaselineSource(
            hasMetadata = version != null,
            scheme = upstreamUri.substringBefore(':').lowercase(),
            container = version?.container,
            videoCodec = version?.sourceVideoCodec,
            serverTranscode = item.startsWithServerTranscode(),
            serverTranscodeAuthorized = usingServerTranscode,
            adaptiveManifest = upstreamUri.isAdaptiveManifest() || version?.container.isAdaptiveContainer(),
            adaptiveManifestSupported = hlsManifest || dashManifest,
            disc = version?.discSource == true,
            discSupported = item.supportsYCoreNativeBluRay(version),
            drm = drmConfiguration != null,
            drmSupported = drmConfiguration?.let { item.supportsCore2Drm(it.scheme) } == true,
            dolbyVision = version?.dolbyVision == true,
            dolbyVisionSupported =
                version?.dolbyVision != true ||
                    version?.dolbyProfile == null ||
                    version?.dolbyProfile in CORE2_DOLBY_TRIAL_PROFILES,
            // PlaybackInfo may fail while the authenticated media URL still works. Missing
            // server metadata must reach the same bounded local truth probes as a file source.
            probedBeforeRouting = usingServerTranscode || item.isFileSourcePlayback || version == null,
        )
    return evaluateCore2NativeBaseline(source)?.userMessage()
}

private fun Core2NativeBaselineBlock.userMessage(): String =
    when (this) {
        Core2NativeBaselineBlock.MissingMetadata -> "YCore Native 缺少片源格式元数据"
        Core2NativeBaselineBlock.UnsupportedScheme -> "YCore Native 暂不支持当前来源协议"
        Core2NativeBaselineBlock.ServerTranscode -> "当前片源未允许所选服务器转码输出"
        Core2NativeBaselineBlock.AdaptiveManifest -> "YCore Native 不支持当前 HLS / DASH 清单结构"
        Core2NativeBaselineBlock.UnsupportedContainer -> "YCore Native 不支持当前封装格式"
        Core2NativeBaselineBlock.UnsupportedVideoCodec ->
            "YCore Native 不支持当前视频编码；已支持 H.264/HEVC/VP9/AV1/VC-1/MPEG-2/ProRes"
        Core2NativeBaselineBlock.Disc -> "YCore Native 当前只支持可寻址的 Blu-ray / BDMV / Blu-ray ISO"
        Core2NativeBaselineBlock.Drm -> "YCore Native 当前只支持已验证容器中的 Widevine DRM"
        Core2NativeBaselineBlock.DolbyVision -> "YCore Native 尚未验证当前杜比视界 Profile"
    }

private fun String.isAdaptiveManifest(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}

private fun String.isHlsManifest(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8")
}

// These are the semantic profiles for which YCore owns strict parser/router paths. Runtime codec
// and display capability checks remain authoritative, so admitting Profile 10 here never implies
// that an AV1 Dolby decoder exists on the current device.
private val CORE2_DOLBY_TRIAL_PROFILES = setOf(5, 7, 8, 10)

private fun PlayerMediaItem.supportsYCoreNativeBluRay(version: com.yfuse.feature.player.PlayerMediaVersion?): Boolean {
    if (version?.discSource != true || !FfmpegNativeBridge.discNavigationAvailable) return false
    val kind =
        detectPlaybackDiscKind(
            container = version.container,
            labelHint = version.label,
            declaredDiscSource = true,
        )
    val scheme = url.substringBefore(':').lowercase()
    return supportsYCoreNativeDiscSource(kind, scheme)
}

internal fun supportsYCoreNativeDiscSource(
    kind: PlaybackDiscKind,
    scheme: String,
): Boolean =
    when (kind) {
        PlaybackDiscKind.BluRay,
        PlaybackDiscKind.Iso,
        -> scheme in setOf("file", "content", "http", "https", "webdav", "webdavs", "smb", "ftp", "yfuse-cloud")
        PlaybackDiscKind.Bdmv -> scheme in setOf("file", "content")
        PlaybackDiscKind.Dvd,
        PlaybackDiscKind.None,
        PlaybackDiscKind.Unknown,
        -> false
    }

private fun String.isDashManifest(): Boolean {
    val path = substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".mpd")
}

private fun String?.isHlsContainer(): Boolean = orEmpty().trim().lowercase() in setOf("hls", "m3u8")

private fun String?.isDashContainer(): Boolean = orEmpty().trim().lowercase() in setOf("dash", "mpd")

private fun String?.isAdaptiveContainer(): Boolean = isHlsContainer() || isDashContainer()

private fun PlayerMediaItem.supportsCore2Drm(scheme: PlaybackDrmScheme): Boolean {
    if (scheme != PlaybackDrmScheme.Widevine) return false
    val container =
        activeVersion
            ?.container
            .orEmpty()
            .trim()
            .lowercase()
    return url.isDashManifest() ||
        url.isHlsManifest() ||
        container in setOf("dash", "mpd", "hls", "m3u8", "mp4", "m4v", "cmaf")
}

internal fun List<PlayerMediaItem>.toCore2MediaItems(
    customUserAgent: String,
    cacheMaximumBytes: Long = 0L,
    initialTrackSelections: Map<String, com.yfuse.core2.api.YInitialTrackSelection> = emptyMap(),
    appVersion: () -> String = { AppBuildConfig.VERSION_NAME },
    localize: (PlayerMediaItem, String) -> String = { _, uri -> uri },
): List<YMediaItem> {
    val headers =
        customUserAgent
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { mapOf(USER_AGENT_HEADER to it) }
            .orEmpty()
    return map { item ->
        item
            .toCore2MediaItem(headers, cacheMaximumBytes, localize, appVersion)
            .copy(initialTrackSelection = initialTrackSelections[item.id]?.orNull())
    }
}

private fun PlayerMediaItem.toCore2MediaItem(
    headers: Map<String, String>,
    cacheMaximumBytes: Long,
    localize: (PlayerMediaItem, String) -> String,
    appVersion: () -> String,
): YMediaItem {
    val usingServerTranscode = usesApprovedCore2ServerTranscode()
    val version = activeVersion
    val upstreamUri = core2UpstreamUri()
    val localizedUri = localize(this, upstreamUri)
    val customSourceHeaders = core2CustomHeaders(upstreamUri)
    val upstreamHeaders = headers + embyPlaybackHeaders(upstreamUri, appVersion) + customSourceHeaders
    return YMediaItem(
        id = id,
        uri = localizedUri,
        title = title,
        mimeType = if (usingServerTranscode) null else version?.container.toCore2ContainerMimeType(),
        headers =
            if (mediaCredentialOriginsMatch(upstreamUri, localizedUri)) {
                upstreamHeaders
            } else {
                // A loopback proxy owns upstream auth; it must not receive it as a client header.
                headers
            },
        originBoundHeaderNames =
            if (mediaCredentialOriginsMatch(
                    upstreamUri,
                    localizedUri,
                )
            ) {
                customSourceHeaders.keys
            } else {
                emptySet()
            },
        sourceKey = yMediaSourceKey(upstreamUri, upstreamHeaders),
        providerKey = serverId,
        playbackSessionId = playSessionId,
        allowNextItemPreparation = canPreloadSource && !usingServerTranscode,
        cacheIdentity = yCoreCacheIdentity().takeUnless { usingServerTranscode },
        cacheMaximumBytes =
            if (usingServerTranscode ||
                customHeaders?.values?.isNotEmpty() ==
                true
            ) {
                0L
            } else {
                cacheMaximumBytes.coerceAtLeast(0L)
            },
        drmConfiguration = drmConfiguration ?: version?.drmConfiguration,
        sourceHints =
            version
                ?.takeUnless { usingServerTranscode }
                ?.let { source ->
                    YMediaSourceHints(
                        container = source.container,
                        bitrateBitsPerSecond = source.sourceBitrateBps?.toLong() ?: 0L,
                        videoCodec = source.sourceVideoCodec,
                        audioCodec = source.sourceAudio,
                        audioChannelCount = source.sourceAudioChannelCount,
                        audioSampleRateHz = source.sourceAudioSampleRateHz,
                        audioTrackCount = source.audioTrackCount,
                        audioCodecs = source.sourceAudioCodecs,
                        dynamicRange = source.sourceDynamicRange,
                        dolbyVision = source.dolbyVision,
                        dolbyVisionProfile = source.dolbyProfile,
                        dolbyRpuPresent = source.sourceDolbyRpuPresent,
                        dolbyEnhancementLayerPresent = source.sourceDolbyEnhancementLayerPresent,
                        dolbyBaseLayerPresent = source.sourceDolbyBaseLayerPresent,
                        dolbyBaseLayerCompatibilityId = source.sourceDolbyBaseLayerCompatibility,
                    )
                },
        transportCredentials = transportCredentials.takeUnless { usingServerTranscode },
        externalSubtitles =
            playbackExternalSubtitles().map { subtitle ->
                YExternalSubtitleSource(
                    uri = subtitle.uri,
                    language = subtitle.language,
                    format =
                        when ((subtitle.codec ?: externalSubtitleFormatHint(subtitle.uri))?.lowercase()) {
                            "srt", "subrip" -> YSubtitleFormat.Srt
                            "vtt", "webvtt" -> YSubtitleFormat.WebVtt
                            "ass" -> YSubtitleFormat.Ass
                            "ssa" -> YSubtitleFormat.Ssa
                            "smi", "sami" -> YSubtitleFormat.Smi
                            "ttml", "dfxp" -> YSubtitleFormat.Ttml
                            "microdvd" -> YSubtitleFormat.MicroDvd
                            else -> null
                        },
                    default = subtitle.default,
                    forced = subtitle.forced,
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

private fun String?.toCore2ContainerMimeType(): String? =
    when (orEmpty().trim().lowercase()) {
        "mkv", "matroska" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp4", "m4v" -> "video/mp4"
        "mov", "quicktime" -> "video/quicktime"
        "ts", "mpegts", "mpeg-ts", "m2ts", "mts" -> "video/mp2t"
        else -> null
    }

private fun PlayerMediaItem.yCoreCacheIdentity(): YCacheIdentity? =
    serverId
        ?.takeIf(String::isNotBlank)
        ?.let { scope ->
            YCacheIdentity(
                scope = scope,
                mediaId = id,
                version = activeVersion?.id.orEmpty(),
            )
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
        "ftp",
        "yfuse-cloud",
        "file",
        "content",
        "android.resource",
    )
private const val USER_AGENT_HEADER = "User-Agent"
