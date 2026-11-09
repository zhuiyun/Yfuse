package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.SEN_PLAYER_DISPLAY_NAME

/** User intent resolved by YCore before a concrete backend is constructed. */
enum class PlaybackOptimizationMode {
    Balanced,
    PowerSaver,
    Quality,
    Compatibility,
}

/** Automatic playback uses YCore; SenPlayer requires an explicit user selection. */
enum class PlaybackEngineSelection {
    Auto,
    LockYCore,
    LockSenPlayer,
    ;

    val lockedEngine: PlayerEngine?
        get() =
            when (this) {
                Auto -> null
                LockYCore -> PlayerEngine.YCore
                LockSenPlayer -> PlayerEngine.SenPlayer
            }

    companion object {
        val selectable: List<PlaybackEngineSelection>
            get() = entries.filter { it.lockedEngine == null || it.lockedEngine in PlayerEngine.selectable }

        fun locked(engine: PlayerEngine): PlaybackEngineSelection =
            when (engine) {
                PlayerEngine.YCore -> LockYCore
                PlayerEngine.SenPlayer -> LockSenPlayer
            }
    }
}

/** The expensive part of the selected pipeline, kept separate from the engine name. */
enum class PlaybackRenderPath {
    PlatformDirect,
    NativeDirect,
    GpuDolbyVision,
    Hdr10BaseLayer,
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
    /**
     * True when the server has already selected the Blu-ray/DVD main feature and [container]
     * describes the original disc rather than the linear stream delivered by PlaybackInfo.
     *
     * This is deliberately distinct from transcoding: an Emby DirectStream can be an untouched
     * M2TS/TS remux that preserves HDR/Dolby Vision, TrueHD/Atmos and PGS. Treating the original
     * MediaSource's `discSource` flag as proof that the URL still points at raw ISO bytes used to
     * force such streams back through server ffmpeg and destroy exactly the original-disc formats
     * the player is trying to preserve.
     */
    val discMainFeatureResolved: Boolean = false,
    val audioCodec: PlaybackAudioCodec? = null,
    val audioChannelCount: Int? = null,
    val durationMs: Long? = null,
    /** Original file size, used for demux/startup policy rather than quality selection. */
    val sourceSizeBytes: Long? = null,
    val probeDepth: PlaybackProbeDepth = PlaybackProbeDepth.ServerMetadata,
) {
    val normalizedContainer: String
        get() = container?.trim()?.uppercase().orEmpty()

    val requiresNativeDemuxer: Boolean
        get() =
            !discMainFeatureResolved &&
                !usingServerTranscode &&
                (
                    discSource ||
                        normalizedContainer in NATIVE_FIRST_CONTAINERS ||
                        isHugeRemoteMov
                )

    /** Large remote MOV files require bounded native metadata probing before playback. */
    val isHugeRemoteMov: Boolean
        get() =
            !localSource &&
                normalizedContainer == "MOV" &&
                (sourceSizeBytes ?: 0L) >= HUGE_REMOTE_MOV_BYTES

    /** Credential-free, URL-free key used by the bounded failure memory. */
    val capabilitySignature: String
        get() =
            buildList {
                add(normalizedContainer.ifEmpty { "UNKNOWN" })
                add(source.videoRequirements.codec?.name ?: "UnknownCodec")
                add(source.width?.resolutionBucket() ?: "UnknownWidth")
                add(source.height?.resolutionBucket() ?: "UnknownHeight")
                add(source.frameRate?.frameRateBucket() ?: "UnknownFps")
                add(source.bitDepth?.toString() ?: "UnknownDepth")
                add(source.hdrFormat?.name ?: "Sdr")
                if (source.dolbyVision) {
                    add(source.dolbyVisionProfile?.let { "DolbyP$it" } ?: "UnknownDolbyProfile")
                    add(source.dolbyEnhancementLayerPresent?.let { "DolbyEL=$it" } ?: "UnknownDolbyEL")
                    add(source.dolbyBaseLayerCompatibilityId?.let { "DolbyBL=$it" } ?: "UnknownDolbyBL")
                }
                add(audioCodec?.name ?: "UnknownAudio")
                add(audioChannelCount?.toString() ?: "UnknownChannels")
                add(discKind.name)
                add(
                    when {
                        discMainFeatureResolved -> "ResolvedDiscMainFeature"
                        discSource || discKind != PlaybackDiscKind.None -> "RawDiscSource"
                        else -> "LinearMedia"
                    },
                )
                add(if (styledSubtitles) "StyledSubtitles" else "PlainSubtitles")
                add(if (drmProtected) "Drm" else "Clear")
                add(if (usingServerTranscode) "Transcode" else "Original")
            }.joinToString("|")
}

data class PlaybackPlan(
    val primaryEngine: PlayerEngine,
    val decoderMode: DecoderMode,
    val renderPath: PlaybackRenderPath,
    val requiresServerTranscode: Boolean,
    /** Includes [primaryEngine] as the first entry. */
    val engineOrder: List<PlayerEngine>,
    val reason: String? = null,
    val dolbyVisionPath: PlaybackDolbyVisionPath = PlaybackDolbyVisionPath.None,
)

/**
 * Plans source handling for the two installed engines. Auto never opts into SenPlayer licensing.
 * This is a request, not output proof: the runtime still verifies codecs, HDR, DRM and surfaces.
 */
@Suppress("UNUSED_PARAMETER")
fun planPlayback(
    probe: PlaybackMediaProbe,
    capabilities: PlaybackDeviceCapabilities,
    preferredEngine: PlayerEngine,
    preferredDecoderMode: DecoderMode,
    allowAudioPassthrough: Boolean = true,
    optimizationMode: PlaybackOptimizationMode = PlaybackOptimizationMode.Balanced,
    engineSelection: PlaybackEngineSelection = PlaybackEngineSelection.Auto,
    excludedEngines: Set<PlayerEngine> = emptySet(),
    engineCosts: Map<PlayerEngine, Int> = emptyMap(),
    videoSupport: PlaybackVideoSupport = capabilities.videoSupport(probe.source.videoRequirements),
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities =
        PlaybackDolbyVisionRuntimeCapabilities.conservative(),
    availableEngines: Set<PlayerEngine> = PlayerEngine.selectable.toSet(),
): PlaybackPlan {
    val requested = engineSelection.lockedEngine ?: PlayerEngine.YCore
    val primary =
        requested.takeIf(availableEngines::contains)
            ?: PlayerEngine.YCore.takeIf(availableEngines::contains)
            ?: error("No selected playback engine is available")
    val discDecision = planDiscPlayback(probe)
    val hdrRoute =
        playbackHdrRoute(
            source = probe.source,
            capabilities = capabilities,
            preferredEngine = primary,
            preferredDecoderMode = preferredDecoderMode,
            videoSupport = videoSupport,
            dolbyVisionRuntime = dolbyVisionRuntime,
            requiresNativeDemuxer = discDecision.requiresNativeEngine || probe.requiresNativeDemuxer,
        )
    val softwareFirst = probe.source.videoRequirements.codec in SOFTWARE_FIRST_VIDEO_CODECS
    val localSoftware =
        primary == PlayerEngine.YCore &&
            !probe.drmProtected &&
            !probe.source.dolbyVision &&
            !probe.usingServerTranscode &&
            (softwareFirst || (videoSupport.isUnsupported && !probe.hasServerTranscode))
    // A server fallback is a clear-stream compatibility operation, never a DRM bypass.
    // Original Dolby stays local unless the viewer explicitly requests server conversion.
    val requiresServer =
        !probe.drmProtected &&
            !probe.source.dolbyVision &&
            !probe.usingServerTranscode &&
            probe.hasServerTranscode &&
            (
                discDecision.requiresServerTranscode ||
                    (
                        primary == PlayerEngine.YCore &&
                            !softwareFirst &&
                            (
                                hdrRoute.requiresServerTranscode ||
                                    (videoSupport.isUnsupported && preferredDecoderMode != DecoderMode.Software)
                            )
                    )
            )
    val reason =
        when {
            probe.drmProtected -> "当前内核尚未支持受保护内容的完整 DRM 播放流程"
            requested != primary -> "所选内核不适用于当前平台，使用 YCore"
            primary == PlayerEngine.SenPlayer -> "已选择 $SEN_PLAYER_DISPLAY_NAME，播放时按原内核规则验证许可"
            requiresServer -> discDecision.reason ?: "设备未确认支持原始格式，使用可用的服务器转码"
            localSoftware && softwareFirst -> "ProRes 交由 YCore FFmpeg 软件解码，实际格式支持由运行时验证"
            localSoftware -> "设备不支持此硬解格式且无服务器转码，尝试 YCore 软件解码"
            hdrRoute.reason != null -> hdrRoute.reason
            discDecision.reason != null -> discDecision.reason
            probe.requiresNativeDemuxer -> "${probe.normalizedContainer} 使用 YCore 原生解封装"
            probe.styledSubtitles -> "使用 YCore 字幕管线，样式支持以实际输出为准"
            primary in excludedEngines -> "当前只有 YCore 自动内核可用；失败后不会自动启用 $SEN_PLAYER_DISPLAY_NAME"
            else -> "使用 YCore 自研播放管线"
        }
    return PlaybackPlan(
        primaryEngine = primary,
        decoderMode =
            when {
                probe.drmProtected -> preferredDecoderMode
                primary == PlayerEngine.SenPlayer -> preferredDecoderMode
                requiresServer || probe.usingServerTranscode -> DecoderMode.Hardware
                localSoftware -> DecoderMode.Software
                probe.source.dolbyVision -> hdrRoute.decoderMode
                optimizationMode == PlaybackOptimizationMode.PowerSaver -> DecoderMode.Hardware
                else -> preferredDecoderMode
            },
        renderPath =
            when {
                requiresServer || probe.usingServerTranscode -> PlaybackRenderPath.ServerTranscode
                primary == PlayerEngine.YCore && hdrRoute.dolbyVisionPath == PlaybackDolbyVisionPath.Hdr10BaseLayer ->
                    PlaybackRenderPath.Hdr10BaseLayer
                else -> PlaybackRenderPath.NativeDirect
            },
        requiresServerTranscode = requiresServer,
        engineOrder = listOf(primary),
        reason = reason,
        dolbyVisionPath = hdrRoute.dolbyVisionPath,
    )
}

private fun Int.resolutionBucket(): String =
    when {
        this >= 3800 -> "Uhd"
        this >= 2500 -> "Qhd"
        this >= 1900 -> "Fhd"
        this >= 1200 -> "Hd"
        else -> "Sd"
    }

private fun Double.frameRateBucket(): String =
    when {
        this >= 100.0 -> "HighFps"
        this >= 50.0 -> "50Plus"
        this >= 29.0 -> "30ish"
        this >= 23.0 -> "24ish"
        else -> "LowFps"
    }

private val NATIVE_FIRST_CONTAINERS =
    setOf(
        "ISO",
        "DVD",
        "BLURAY",
        "BDMV",
        "AVI",
        "FLV",
    )

private const val HUGE_REMOTE_MOV_BYTES = 64L * 1024L * 1024L * 1024L

/**
 * Platform decoders are not a useful first attempt for these formats on Android. Keep the original
 * file on bundled FFmpeg even when the server advertises a transcode path; server transcode remains
 * the runtime fallback if native decode actually fails.
 */
private val SOFTWARE_FIRST_VIDEO_CODECS = setOf(PlaybackVideoCodec.ProRes)
