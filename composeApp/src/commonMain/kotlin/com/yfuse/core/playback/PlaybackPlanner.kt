package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

/** User intent resolved by YCore before a concrete backend is constructed. */
enum class PlaybackOptimizationMode {
    Balanced,
    PowerSaver,
    Quality,
    Compatibility,
}

/** The expensive part of the selected pipeline, kept separate from the engine name. */
enum class PlaybackRenderPath {
    PlatformDirect,
    NativeDirect,
    GpuToneMapped,
    ServerTranscode,
}

/**
 * Immutable facts discovered before playback starts.
 *
 * Fast facts come from Emby PlaybackInfo and a bounded platform extractor enriches the same model
 * after launch without coupling the planner to Android or a concrete backend.
 */
data class PlaybackMediaProbe(
    val container: String?,
    val discSource: Boolean,
    val source: PlaybackSourceRequirements,
    val hasServerTranscode: Boolean,
    val styledSubtitles: Boolean = false,
    val drmProtected: Boolean = false,
    val usingServerTranscode: Boolean = false,
    val discKind: PlaybackDiscKind = PlaybackDiscKind.None,
    val localSource: Boolean = false,
    val audioCodec: PlaybackAudioCodec? = null,
    val audioChannelCount: Int? = null,
    val durationMs: Long? = null,
    val probeDepth: PlaybackProbeDepth = PlaybackProbeDepth.ServerMetadata,
) {
    val normalizedContainer: String
        get() = container?.trim()?.uppercase().orEmpty()

    val requiresNativeDemuxer: Boolean
        get() = discSource || normalizedContainer in NATIVE_FIRST_CONTAINERS

    /** Credential-free, URL-free key used by the bounded failure memory. */
    val capabilitySignature: String
        get() =
            listOf(
                normalizedContainer.ifEmpty { "UNKNOWN" },
                source.videoRequirements.codec?.name ?: "UnknownCodec",
                source.width?.resolutionBucket() ?: "UnknownWidth",
                source.height?.resolutionBucket() ?: "UnknownHeight",
                source.frameRate?.frameRateBucket() ?: "UnknownFps",
                source.bitDepth?.toString() ?: "UnknownDepth",
                source.hdrFormat?.name ?: "Sdr",
                audioCodec?.name ?: "UnknownAudio",
                audioChannelCount?.toString() ?: "UnknownChannels",
                discKind.name,
                if (styledSubtitles) "StyledSubtitles" else "PlainSubtitles",
                if (drmProtected) "Drm" else "Clear",
                if (usingServerTranscode) "Transcode" else "Original",
            ).joinToString("|")
}

data class PlaybackPlan(
    val primaryEngine: PlayerEngine,
    val decoderMode: DecoderMode,
    val renderPath: PlaybackRenderPath,
    val requiresServerTranscode: Boolean,
    /** Includes [primaryEngine] as the first entry. */
    val engineOrder: List<PlayerEngine>,
    val reason: String? = null,
)

/**
 * Chooses components rather than treating Exo, mpv and MDK as equivalent opaque players.
 *
 * Platform hardware decode remains the efficient path. Native engines are selected for known
 * demux/subtitle gaps and GPU tone mapping. Server transcode wins over local software decoding
 * when the device has already rejected the exact source format.
 */
fun planPlayback(
    probe: PlaybackMediaProbe,
    capabilities: PlaybackDeviceCapabilities,
    preferredEngine: PlayerEngine,
    preferredDecoderMode: DecoderMode,
    optimizationMode: PlaybackOptimizationMode = PlaybackOptimizationMode.Balanced,
    excludedEngines: Set<PlayerEngine> = emptySet(),
    engineCosts: Map<PlayerEngine, Int> = emptyMap(),
    videoSupport: PlaybackVideoSupport = capabilities.videoSupport(probe.source.videoRequirements),
): PlaybackPlan {
    val discDecision = planDiscPlayback(probe)
    val audioNeedsNative =
        probe.audioCodec != null && probe.audioCodec !in capabilities.directPlayableAudio
    val hdrRoute =
        playbackHdrRoute(
            source = probe.source,
            capabilities = capabilities,
            preferredEngine = preferredEngine,
            preferredDecoderMode = preferredDecoderMode,
            videoSupport = videoSupport,
        )
    val discNeedsServer = discDecision.requiresServerTranscode
    val powerSaverToneMapTranscode =
        optimizationMode == PlaybackOptimizationMode.PowerSaver &&
            hdrRoute.engine == PlayerEngine.Mpv &&
            hdrRoute.reason != null &&
            probe.hasServerTranscode
    val requiresServerTranscode =
        hdrRoute.requiresServerTranscode || discNeedsServer || powerSaverToneMapTranscode
    val strictPlatformPath =
        !probe.usingServerTranscode &&
            (
                probe.drmProtected ||
                    (
                        probe.source.needsDolbyDecoder &&
                            capabilities.supportsDolbyVisionOutput &&
                            !requiresServerTranscode
                    )
            )

    val contentEngine =
        when {
            strictPlatformPath -> PlayerEngine.Exo
            discNeedsServer -> PlayerEngine.Exo
            powerSaverToneMapTranscode -> PlayerEngine.Exo
            hdrRoute.engine != preferredEngine -> hdrRoute.engine
            preferredDecoderMode == DecoderMode.Software -> PlayerEngine.Mpv
            discDecision.requiresNativeEngine -> PlayerEngine.Mpv
            probe.requiresNativeDemuxer || probe.styledSubtitles -> PlayerEngine.Mpv
            audioNeedsNative -> PlayerEngine.Mpv
            optimizationMode == PlaybackOptimizationMode.PowerSaver -> PlayerEngine.Exo
            optimizationMode == PlaybackOptimizationMode.Quality && probe.source.hdrFormat != null ->
                PlayerEngine.Exo
            optimizationMode == PlaybackOptimizationMode.Compatibility -> PlayerEngine.Mpv
            else -> preferredEngine
        }

    val requestedOrder =
        when {
            strictPlatformPath -> listOf(PlayerEngine.Exo)
            optimizationMode == PlaybackOptimizationMode.PowerSaver ->
                listOf(contentEngine, PlayerEngine.Exo, PlayerEngine.Mdk, PlayerEngine.Mpv)
            optimizationMode == PlaybackOptimizationMode.Quality ->
                listOf(contentEngine, PlayerEngine.Mpv, PlayerEngine.Exo, PlayerEngine.Mdk)
            optimizationMode == PlaybackOptimizationMode.Compatibility ->
                listOf(contentEngine, PlayerEngine.Mpv, PlayerEngine.Mdk, PlayerEngine.Exo)
            else ->
                listOf(contentEngine, preferredEngine, PlayerEngine.Exo, PlayerEngine.Mpv, PlayerEngine.Mdk)
        }.filter(PlayerEngine.selectable::contains).distinct()

    val performanceRankingEligible =
        optimizationMode == PlaybackOptimizationMode.Balanced &&
            !strictPlatformPath &&
            !discDecision.requiresNativeEngine &&
            !probe.requiresNativeDemuxer &&
            !probe.styledSubtitles &&
            !audioNeedsNative &&
            hdrRoute.engine == preferredEngine
    val rankedOrder =
        if (performanceRankingEligible) {
            requestedOrder
                .withIndex()
                .sortedWith(
                    compareBy<IndexedValue<PlayerEngine>>(
                        { engineCosts[it.value] ?: 0 },
                        IndexedValue<PlayerEngine>::index,
                    ),
                ).map(IndexedValue<PlayerEngine>::value)
        } else {
            requestedOrder
        }
    val availableOrder = rankedOrder.filterNot(excludedEngines::contains)
    val primary = availableOrder.firstOrNull() ?: requestedOrder.first()
    val finalOrder = listOf(primary) + availableOrder.filterNot { it == primary }
    val renderPath =
        when {
            requiresServerTranscode || probe.usingServerTranscode -> PlaybackRenderPath.ServerTranscode
            primary == PlayerEngine.Exo -> PlaybackRenderPath.PlatformDirect
            hdrRoute.engine == PlayerEngine.Mpv && hdrRoute.reason != null ->
                PlaybackRenderPath.GpuToneMapped
            else -> PlaybackRenderPath.NativeDirect
        }
    val reason =
        when {
            discDecision.reason != null -> discDecision.reason
            powerSaverToneMapTranscode -> "当前显示设备不支持片源 HDR，省电模式使用服务器色调映射"
            hdrRoute.reason != null -> hdrRoute.reason
            preferredDecoderMode == DecoderMode.Software -> "用户要求软件解码，选择 FFmpeg 兼容管线"
            probe.requiresNativeDemuxer -> "${probe.normalizedContainer} 需要原生解封装管线"
            audioNeedsNative ->
                "平台未声明 ${probe.audioCodec.name} 音频解码，使用原生音频管线"
            probe.styledSubtitles -> "复杂字幕需要原生样式渲染"
            primary != contentEngine -> "已避开当前设备上重复失败的 ${contentEngine.label}"
            optimizationMode == PlaybackOptimizationMode.PowerSaver -> "省电模式优先平台硬解直出"
            optimizationMode == PlaybackOptimizationMode.Compatibility -> "兼容模式优先原生解封装"
            else -> null
        }

    return PlaybackPlan(
        primaryEngine = primary,
        decoderMode =
            when {
                requiresServerTranscode -> DecoderMode.Hardware
                primary == PlayerEngine.Exo && optimizationMode == PlaybackOptimizationMode.PowerSaver ->
                    DecoderMode.Hardware
                else -> hdrRoute.decoderMode
            },
        renderPath = renderPath,
        requiresServerTranscode = requiresServerTranscode,
        engineOrder = finalOrder,
        reason = reason,
    )
}

private fun Int.resolutionBucket(): String =
    when {
        this <= 0 -> "Unknown"
        this <= 720 -> "720"
        this <= 1_080 -> "1080"
        this <= 1_440 -> "1440"
        this <= 2_160 -> "2160"
        else -> "Above2160"
    }

private fun Double.frameRateBucket(): String =
    when {
        this <= 0.0 -> "Unknown"
        this <= 30.5 -> "30"
        this <= 60.5 -> "60"
        else -> "Above60"
    }

private val NATIVE_FIRST_CONTAINERS =
    setOf(
        "ISO",
        "BDMV",
        "DVD",
        "AVI",
        "ASF",
        "OGM",
        "RM",
        "RMVB",
        "VOB",
        "WMV",
    )
