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

/**
 * Chooses the least destructive HDR path before a backend is constructed.
 *
 * Dolby is always kept on the client. A complete platform Dolby pipeline uses Exo; otherwise mpv
 * owns local Dolby metadata processing/tone mapping. Compatible streams may use their HDR10 base
 * layer, while generic HDR on an SDR display also uses mpv's BT.2390 tone-mapping path.
 */
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
        engine = if (compatibleOutput) preferredEngine else PlayerEngine.Mpv,
        decoderMode = DecoderMode.Hardware,
        requiresServerTranscode = false,
        reason = "当前显示设备不支持片源 HDR，使用播放器色调映射".takeUnless { compatibleOutput },
    )
}
