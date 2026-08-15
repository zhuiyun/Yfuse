package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

data class PlaybackSourceRequirements(
    val dolbyVision: Boolean,
    val needsDolbyDecoder: Boolean,
    val dynamicRange: String?,
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
)

/**
 * Chooses the least destructive HDR path before a backend is constructed.
 *
 * Dolby-only streams cannot be repaired by ordinary HEVC software decoding. Compatible Dolby
 * streams may fall back to their HDR10 base layer; generic HDR on an SDR display is sent through
 * mpv because that backend owns the app's BT.2390 tone-mapping path.
 */
fun playbackHdrRoute(
    source: PlaybackSourceRequirements,
    capabilities: PlaybackDeviceCapabilities,
    preferredEngine: PlayerEngine,
    preferredDecoderMode: DecoderMode,
    videoSupport: PlaybackVideoSupport = capabilities.videoSupport(source.videoRequirements),
): PlaybackHdrRoute {
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
    if (source.needsDolbyDecoder) {
        return if (capabilities.supportsDolbyVisionOutput) {
            PlaybackHdrRoute(
                engine = PlayerEngine.Exo,
                decoderMode = DecoderMode.Hardware,
                requiresServerTranscode = false,
            )
        } else {
            PlaybackHdrRoute(
                engine = preferredEngine,
                decoderMode = DecoderMode.Hardware,
                requiresServerTranscode = true,
                reason = "设备缺少 Dolby Vision 显示或硬件解码能力",
            )
        }
    }

    val hdrFormat = source.hdrFormat
        ?: return PlaybackHdrRoute(preferredEngine, preferredDecoderMode, false)
    if (hdrFormat == PlaybackHdrFormat.DolbyVision && capabilities.supportsDolbyVisionOutput) {
        return PlaybackHdrRoute(PlayerEngine.Exo, DecoderMode.Hardware, false)
    }

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
