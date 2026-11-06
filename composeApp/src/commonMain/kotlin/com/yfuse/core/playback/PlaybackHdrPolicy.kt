package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

data class PlaybackSourceRequirements(
    val dolbyVision: Boolean,
    val needsDolbyDecoder: Boolean,
    val dynamicRange: String?,
    /** Semantic Dolby profile (5/7/8/9), not Android CodecProfileLevel. */
    val dolbyVisionProfile: Int? = null,
    val dolbyRpuPresent: Boolean? = null,
    val dolbyEnhancementLayerPresent: Boolean? = null,
    val dolbyBaseLayerPresent: Boolean? = null,
    /** Profile suffix/BL signal compatibility id: for example 1 in P8.1. */
    val dolbyBaseLayerCompatibilityId: Int? = null,
    val videoCodec: PlaybackVideoCodec? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val bitrateBitsPerSecond: Int? = null,
    val bitDepth: Int? = null,
    val videoLevel: Double? = null,
) {
    val hdrFormat: PlaybackHdrFormat?
        get() {
            if (dolbyVision) return PlaybackHdrFormat.DolbyVision
            val normalized = dynamicRange?.uppercase().orEmpty()
            return when {
                "DOLBY" in normalized || "DOVI" in normalized -> PlaybackHdrFormat.DolbyVision
                "HDR10+" in normalized || "HDR10PLUS" in normalized -> PlaybackHdrFormat.Hdr10Plus
                "HLG" in normalized -> PlaybackHdrFormat.Hlg
                "HDR" in normalized || "PQ" in normalized -> PlaybackHdrFormat.Hdr10
                else -> null
            }
        }

    val videoRequirements: PlaybackVideoRequirements
        get() =
            PlaybackVideoRequirements(
                codec = if (needsDolbyDecoder) PlaybackVideoCodec.DolbyVision else videoCodec,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrateBitsPerSecond = bitrateBitsPerSecond,
                bitDepth = bitDepth,
                level = videoLevel,
                hdrFormat =
                    if (dolbyVision && !needsDolbyDecoder) {
                        PlaybackHdrFormat.Hdr10
                    } else {
                        hdrFormat
                    },
            )
}

data class PlaybackHdrRoute(
    val engine: PlayerEngine,
    val decoderMode: DecoderMode,
    val requiresServerTranscode: Boolean,
    val reason: String? = null,
    val dolbyVisionPath: PlaybackDolbyVisionPath = PlaybackDolbyVisionPath.None,
    val stripDolbyVisionToBaseLayer: Boolean = false,
)

/** Plans HDR requests without treating a native engine name as verified output capability. */
fun playbackHdrRoute(
    source: PlaybackSourceRequirements,
    capabilities: PlaybackDeviceCapabilities,
    preferredEngine: PlayerEngine,
    preferredDecoderMode: DecoderMode,
    videoSupport: PlaybackVideoSupport = capabilities.videoSupport(source.videoRequirements),
    dolbyVisionRuntime: PlaybackDolbyVisionRuntimeCapabilities =
        PlaybackDolbyVisionRuntimeCapabilities.conservative(),
    requiresNativeDemuxer: Boolean = false,
): PlaybackHdrRoute {
    if (preferredEngine == PlayerEngine.SenPlayer) {
        return PlaybackHdrRoute(
            engine = preferredEngine,
            decoderMode = preferredDecoderMode,
            requiresServerTranscode = false,
            reason = "SenPlayer 的 HDR 输出需由实际播放验证".takeIf { source.hdrFormat != null },
            dolbyVisionPath =
                if (source.dolbyVision) {
                    PlaybackDolbyVisionPath.Unverified
                } else {
                    PlaybackDolbyVisionPath.None
                },
        )
    }
    if (source.dolbyVision) {
        val dolbyRoute =
            playbackDolbyVisionRoute(
                source = source,
                capabilities = capabilities,
                runtime = dolbyVisionRuntime,
                requiresNativeDemuxer = requiresNativeDemuxer,
            )
        return PlaybackHdrRoute(
            engine = dolbyRoute.engine,
            decoderMode = dolbyRoute.decoderMode,
            requiresServerTranscode = false,
            reason = dolbyRoute.reason,
            dolbyVisionPath = dolbyRoute.path,
            stripDolbyVisionToBaseLayer = dolbyRoute.stripDolbyVisionToBaseLayer,
        )
    }
    if (
        videoSupport.isUnsupported &&
        (source.hdrFormat != null || preferredDecoderMode == DecoderMode.Hardware)
    ) {
        return PlaybackHdrRoute(
            engine = preferredEngine,
            decoderMode = DecoderMode.Hardware,
            requiresServerTranscode = true,
            reason = videoSupport.detail,
        )
    }
    val hdrFormat =
        source.hdrFormat
            ?: return PlaybackHdrRoute(preferredEngine, preferredDecoderMode, false)

    val compatibleOutput =
        when (hdrFormat) {
            PlaybackHdrFormat.DolbyVision ->
                capabilities.supportsHdrOutput(
                    PlaybackHdrFormat.Hdr10,
                    PlaybackVideoCodec.Hevc,
                )
            else -> capabilities.supportsHdrOutput(hdrFormat)
        }
    return PlaybackHdrRoute(
        engine = preferredEngine,
        decoderMode = DecoderMode.Hardware,
        requiresServerTranscode = !compatibleOutput,
        reason = "当前显示设备未确认支持片源 HDR；需要服务器色调映射或运行时验证本地输出".takeUnless { compatibleOutput },
    )
}
