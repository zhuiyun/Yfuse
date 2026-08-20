package com.yfuse.core2.strategy

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoRequirement

enum class YDemuxPath {
    Platform,
    Enhanced,
    Software,
}

enum class YDecodePath {
    Hardware,
    Software,
}

enum class YRenderPath {
    Tunnel,
    SurfaceDirect,
    Gpu,
}

data class YPlaybackRequest(
    val container: YContainer,
    val video: YVideoRequirement,
    val audio: YAudioRequirement? = null,
    val platformDemuxSupported: Boolean,
    val enhancedDemuxSupported: Boolean = true,
    /** HDR-compatible base layer, e.g. HDR10 for Dolby Vision Profile 8.1. */
    val fallbackHdrType: YHdrType? = null,
    val preferTunnel: Boolean = true,
    val allowAudioPassthrough: Boolean = true,
)

data class YPlaybackPlan(
    val route: YPlaybackRoute,
    val demuxPath: YDemuxPath,
    val decodePath: YDecodePath,
    val renderPath: YRenderPath,
    val outputHdrType: YHdrType,
    val decoderName: String? = null,
    val nativeAudio: Boolean = true,
    val audioPath: YAudioOutputPath = YAudioOutputPath.DecodePcm,
    /** True when the original HDR representation cannot be used and a compatible base is selected. */
    val usesHdrFallback: Boolean = false,
    val reason: String,
)

fun interface YPlaybackStrategy {
    fun plan(
        request: YPlaybackRequest,
        capabilities: YDeviceCapabilities,
    ): YPlaybackPlan
}

/**
 * Core2's deterministic route planner.
 *
 * Policy order is intentionally hardware-first: keep the compressed stream in the platform
 * hardware path whenever possible; use enhanced demux only to make the bitstream acceptable to
 * that path; use GPU processing only when native display output cannot satisfy the requested
 * dynamic range; software decoding is the terminal compatibility fallback.
 */
class DefaultYPlaybackStrategy : YPlaybackStrategy {
    override fun plan(
        request: YPlaybackRequest,
        capabilities: YDeviceCapabilities,
    ): YPlaybackPlan {
        val demuxPath =
            when {
                request.platformDemuxSupported -> YDemuxPath.Platform
                request.enhancedDemuxSupported -> YDemuxPath.Enhanced
                else -> null
            }

        val originalDecoder = capabilities.bestDecoder(request.video)
        val fallbackRequirement =
            request.fallbackHdrType
                ?.takeIf { it != request.video.hdrType }
                ?.let {
                    request.video.copy(
                        hdrType = it,
                        dolbyVisionProfile = null,
                    )
                }
        val fallbackDecoder = fallbackRequirement?.let(capabilities::bestDecoder)
        val selectedRequirement =
            when {
                originalDecoder != null -> request.video
                fallbackDecoder != null -> requireNotNull(fallbackRequirement)
                else -> null
            }
        val selectedDecoder = originalDecoder ?: fallbackDecoder
        val usesHdrFallback = originalDecoder == null && fallbackDecoder != null
        val audioCapabilities =
            if (request.allowAudioPassthrough) {
                capabilities
            } else {
                capabilities.copy(audioPassthrough = emptySet())
            }
        val audioPath = audioCapabilities.audioOutputPath(request.audio)
        val nativeAudio = request.audio == null || audioPath != YAudioOutputPath.None

        if (
            demuxPath != null &&
            selectedDecoder != null &&
            selectedRequirement != null &&
            nativeAudio
        ) {
            val displayCanPresent = capabilities.supportsDisplayHdr(selectedRequirement.hdrType)
            val renderPath =
                when {
                    !displayCanPresent -> YRenderPath.Gpu
                    demuxPath == YDemuxPath.Platform &&
                        canUseNativeTunnel(request, capabilities, selectedDecoder) -> YRenderPath.Tunnel
                    else -> YRenderPath.SurfaceDirect
                }
            val route =
                when {
                    renderPath == YRenderPath.Gpu -> YPlaybackRoute.GpuEnhanced
                    demuxPath == YDemuxPath.Enhanced -> YPlaybackRoute.NativeEnhanced
                    renderPath == YRenderPath.Tunnel -> YPlaybackRoute.NativeTunnel
                    else -> YPlaybackRoute.NativeDirect
                }
            val outputHdr =
                if (displayCanPresent) selectedRequirement.hdrType else YHdrType.Sdr
            return YPlaybackPlan(
                route = route,
                demuxPath = demuxPath,
                decodePath = YDecodePath.Hardware,
                renderPath = renderPath,
                outputHdrType = outputHdr,
                decoderName = selectedDecoder.name,
                nativeAudio = true,
                audioPath = audioPath,
                usesHdrFallback = usesHdrFallback,
                reason =
                    when {
                        usesHdrFallback ->
                            "Original HDR path unsupported; use compatible ${selectedRequirement.hdrType} base layer"
                        demuxPath == YDemuxPath.Enhanced ->
                            "Platform extractor is insufficient; normalize compressed samples then keep hardware decode"
                        renderPath == YRenderPath.Tunnel ->
                            "Platform demux, tunneled hardware video and HW_AV_SYNC audio are available"
                        renderPath == YRenderPath.Gpu ->
                            "Hardware decode is available but the display cannot present ${selectedRequirement.hdrType}; tone-map through GPU"
                        else ->
                            "Use platform/native hardware decode with direct Surface presentation"
                    },
            )
        }

        return YPlaybackPlan(
            route = YPlaybackRoute.SoftwareFallback,
            demuxPath = YDemuxPath.Software,
            decodePath = YDecodePath.Software,
            renderPath = YRenderPath.Gpu,
            outputHdrType =
                if (capabilities.supportsDisplayHdr(request.video.hdrType)) {
                    request.video.hdrType
                } else {
                    YHdrType.Sdr
                },
            nativeAudio = nativeAudio,
            audioPath = audioPath,
            reason =
                if (!nativeAudio) {
                    "Native video route exists but the selected audio codec has no safe platform decode path"
                } else {
                    "No compatible hardware route exists; use universal software decode fallback"
                },
        )
    }
}
