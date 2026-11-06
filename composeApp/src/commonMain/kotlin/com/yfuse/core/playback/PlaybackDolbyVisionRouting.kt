package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

/** A Dolby route must be distinguished from an unverified request to the native runtime. */
enum class PlaybackDolbyVisionPath {
    None,
    Unverified,
    MediaCodecNative,
    Hdr10BaseLayer,
}

/** Positive facts from the current YCore runtime, never inferred from its engine name. */
data class PlaybackDolbyVisionRuntimeCapabilities(
    val verifiedNativeDolbyVision: Boolean = false,
    val verifiedHdr10BaseLayer: Boolean = false,
) {
    companion object {
        fun conservative() = PlaybackDolbyVisionRuntimeCapabilities()
    }
}

data class PlaybackDolbyVisionRoute(
    val path: PlaybackDolbyVisionPath,
    val engine: PlayerEngine,
    val decoderMode: DecoderMode,
    val stripDolbyVisionToBaseLayer: Boolean,
    val reason: String,
)

/** Display capabilities alone prove neither native Dolby output nor FEL/RPU processing. */
@Suppress("UNUSED_PARAMETER")
fun playbackDolbyVisionRoute(
    source: PlaybackSourceRequirements,
    capabilities: PlaybackDeviceCapabilities,
    runtime: PlaybackDolbyVisionRuntimeCapabilities,
    requiresNativeDemuxer: Boolean = false,
): PlaybackDolbyVisionRoute {
    require(source.dolbyVision) { "Dolby routing requires a Dolby Vision source" }
    val enhancementLayer = source.dolbyVisionProfile == 7 && source.dolbyEnhancementLayerPresent != false
    val compatibleBase =
        source.dolbyBaseLayerPresent != false &&
            (
                source.dolbyVisionProfile == 7 ||
                    (source.dolbyVisionProfile == 8 && source.dolbyBaseLayerCompatibilityId == 1) ||
                    (!source.needsDolbyDecoder && source.dolbyVisionProfile == null)
            )
    if (runtime.verifiedNativeDolbyVision && !enhancementLayer && capabilities.supportsDolbyVisionOutput) {
        return PlaybackDolbyVisionRoute(
            PlaybackDolbyVisionPath.MediaCodecNative,
            PlayerEngine.YCore,
            DecoderMode.Hardware,
            false,
            "当前 YCore 管线已确认原生 Dolby Vision 输出",
        )
    }
    if (runtime.verifiedHdr10BaseLayer &&
        compatibleBase &&
        capabilities.supportsHdrOutput(PlaybackHdrFormat.Hdr10, source.videoCodec ?: PlaybackVideoCodec.Hevc)
    ) {
        return PlaybackDolbyVisionRoute(
            PlaybackDolbyVisionPath.Hdr10BaseLayer,
            PlayerEngine.YCore,
            DecoderMode.Hardware,
            true,
            "当前 YCore 管线已确认 HDR10 兼容基础层；不代表完整 Dolby Vision 或 FEL 输出",
        )
    }
    return PlaybackDolbyVisionRoute(
        PlaybackDolbyVisionPath.Unverified,
        PlayerEngine.YCore,
        DecoderMode.Hardware,
        false,
        "Dolby Vision 交由 YCore 运行时验证；当前未确认原生输出、基础层转换或增强层处理",
    )
}
