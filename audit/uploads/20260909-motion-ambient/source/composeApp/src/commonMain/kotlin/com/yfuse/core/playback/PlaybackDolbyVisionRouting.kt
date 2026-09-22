package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

/** Concrete Dolby pipeline selected before constructing a backend. */
enum class PlaybackDolbyVisionPath {
    None,

    /** Android MediaCodec plus a Dolby-capable display surface. */
    MediaCodecNative,

    /** Verified mpv gpu-next/libplacebo RPU and P7 enhancement-layer processing. */
    MpvGpuNext,

    /** RPU and enhancement layer are removed; the HDR10 base layer is rendered. */
    Hdr10BaseLayer,

    /** Client-side HDR/Dolby processing followed by SDR tone mapping. */
    SdrToneMap,
}

/** Runtime facts not inferable from the screen or MediaCodec registry. */
data class PlaybackDolbyVisionRuntimeCapabilities(
    val verifiedMpvRpu: Boolean,
    val verifiedMpvFel: Boolean,
    val fullFelGpuCapable: Boolean,
) {
    val canProcessProfileSevenEnhancementLayer: Boolean
        get() = verifiedMpvRpu && verifiedMpvFel && fullFelGpuCapable

    companion object {
        fun conservative() = PlaybackDolbyVisionRuntimeCapabilities(false, false, false)
    }
}

data class PlaybackDolbyVisionRoute(
    val path: PlaybackDolbyVisionPath,
    val engine: PlayerEngine,
    val decoderMode: DecoderMode,
    val stripDolbyVisionToBaseLayer: Boolean,
    val reason: String,
)

/**
 * Selects a wholly local Dolby route. The server is intentionally absent: Yfuse preserves the
 * original stream and lets MediaCodec or the verified mpv/libplacebo stack process it on-device.
 */
fun playbackDolbyVisionRoute(
    source: PlaybackSourceRequirements,
    capabilities: PlaybackDeviceCapabilities,
    runtime: PlaybackDolbyVisionRuntimeCapabilities,
    requiresNativeDemuxer: Boolean = false,
): PlaybackDolbyVisionRoute {
    require(source.dolbyVision) { "Dolby routing requires a Dolby Vision source" }

    val profile = source.dolbyVisionProfile
    val profileSevenWithEnhancement =
        profile == 7 && source.dolbyEnhancementLayerPresent != false
    val hdr10BaseAvailable =
        source.dolbyBaseLayerPresent != false &&
            (
                profile == 7 ||
                    (profile == 8 && source.dolbyBaseLayerCompatibilityId == 1) ||
                    (!source.needsDolbyDecoder && profile == null)
            )
    val hdr10Output =
        capabilities.supportsHdrOutput(
            PlaybackHdrFormat.Hdr10,
            source.videoCodec ?: PlaybackVideoCodec.Hevc,
        )

    if (profileSevenWithEnhancement && runtime.canProcessProfileSevenEnhancementLayer) {
        val output =
            when {
                capabilities.supportsDolbyVisionOutput -> "Dolby 屏幕"
                hdr10Output -> "HDR10 屏幕"
                else -> "SDR 色调映射"
            }
        return PlaybackDolbyVisionRoute(
            path = PlaybackDolbyVisionPath.MpvGpuNext,
            engine = PlayerEngine.Mpv,
            decoderMode = DecoderMode.Hardware,
            stripDolbyVisionToBaseLayer = false,
            reason = "P7 增强层使用 MPV gpu-next 完整处理，输出到$output",
        )
    }

    val profileEightOne =
        profile == 8 && source.dolbyBaseLayerCompatibilityId == 1
    if (
        profileEightOne &&
        capabilities.supportsDolbyVisionOutput &&
        !requiresNativeDemuxer
    ) {
        return PlaybackDolbyVisionRoute(
            path = PlaybackDolbyVisionPath.MediaCodecNative,
            engine = PlayerEngine.Exo,
            decoderMode = DecoderMode.Hardware,
            stripDolbyVisionToBaseLayer = false,
            reason = "Dolby Vision P8.1 使用 MediaCodec 原生 Dolby Vision 输出",
        )
    }

    if (
        !profileSevenWithEnhancement &&
        capabilities.supportsDolbyVisionOutput &&
        !requiresNativeDemuxer
    ) {
        return PlaybackDolbyVisionRoute(
            path = PlaybackDolbyVisionPath.MediaCodecNative,
            engine = PlayerEngine.Exo,
            decoderMode = DecoderMode.Hardware,
            stripDolbyVisionToBaseLayer = false,
            reason = "片源使用 MediaCodec 原生 Dolby Vision 输出",
        )
    }

    if (hdr10BaseAvailable) {
        val outputPath =
            if (hdr10Output) {
                PlaybackDolbyVisionPath.Hdr10BaseLayer
            } else {
                PlaybackDolbyVisionPath.SdrToneMap
            }
        val why =
            when {
                profileSevenWithEnhancement && !runtime.fullFelGpuCapable ->
                    "GPU 性能预算不足，P7 使用 HDR10 基础层"
                profileSevenWithEnhancement ->
                    "当前原生包未验证 P7 完整处理，使用 HDR10 基础层"
                else -> "当前屏幕不支持 Dolby Vision，使用 HDR10 兼容基础层"
            }
        return PlaybackDolbyVisionRoute(
            path = outputPath,
            engine = PlayerEngine.Mpv,
            decoderMode = DecoderMode.Hardware,
            stripDolbyVisionToBaseLayer = true,
            reason =
                if (hdr10Output) {
                    why
                } else {
                    "${why}并在客户端映射到 SDR"
                },
        )
    }

    return PlaybackDolbyVisionRoute(
        path = PlaybackDolbyVisionPath.SdrToneMap,
        engine = PlayerEngine.Mpv,
        decoderMode = DecoderMode.Software,
        stripDolbyVisionToBaseLayer = false,
        reason = "设备没有可用 Dolby Vision 输出，使用 MPV 在客户端解码并映射到 SDR",
    )
}
