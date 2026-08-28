package com.yfuse.core2.strategy

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.hdr.YHdrRouteDecision
import com.yfuse.core2.hdr.YHdrRouter

enum class YDemuxPath {
    Platform,
    Enhanced,
    Software,
}

enum class YDecodePath {
    Hardware,
    PlatformSoftware,
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
    val softwareAudioDecode: Boolean = false,
    val softwareVideoToneMap: Boolean = false,
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
        val originalNativeOutput =
            originalDecoder != null && capabilities.supportsDisplayHdr(request.video.hdrType)
        val fallbackNativeOutput =
            fallbackDecoder != null &&
                capabilities.supportsDisplayHdr(requireNotNull(fallbackRequirement).hdrType)
        val usesHdrFallback =
            when {
                originalNativeOutput -> false
                fallbackNativeOutput -> true
                originalDecoder != null -> false
                fallbackDecoder != null -> true
                else -> false
            }
        val selectedRequirement =
            when {
                usesHdrFallback -> fallbackRequirement
                originalDecoder != null -> request.video
                fallbackDecoder != null -> fallbackRequirement
                else -> null
            }
        val selectedDecoder = if (usesHdrFallback) fallbackDecoder else originalDecoder ?: fallbackDecoder
        val decodePath =
            if (selectedDecoder?.hardwareAccelerated == false) {
                YDecodePath.PlatformSoftware
            } else {
                YDecodePath.Hardware
            }
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
            val hdrRoute = YHdrRouter.decide(selectedRequirement.hdrType, capabilities)
            val displayCanPresent = hdrRoute is YHdrRouteDecision.Native
            if (
                !displayCanPresent &&
                request.enhancedDemuxSupported &&
                !request.video.secureDecodeRequired &&
                selectedRequirement.hdrType.supportsOwnedSoftwareToneMap()
            ) {
                return YPlaybackPlan(
                    route = YPlaybackRoute.SoftwareFallback,
                    demuxPath = YDemuxPath.Enhanced,
                    decodePath = YDecodePath.Software,
                    renderPath = YRenderPath.Gpu,
                    outputHdrType = YHdrType.Sdr,
                    nativeAudio = true,
                    audioPath = audioPath,
                    softwareVideoToneMap = true,
                    usesHdrFallback = usesHdrFallback,
                    reason =
                        "Display cannot present ${selectedRequirement.hdrType}; " +
                            "decode and tone-map to SDR inside YCore",
                )
            }
            val renderPath =
                when {
                    !displayCanPresent -> YRenderPath.Gpu
                    demuxPath == YDemuxPath.Platform &&
                        selectedDecoder.hardwareAccelerated &&
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
                decodePath = decodePath,
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
                        !selectedDecoder.hardwareAccelerated ->
                            "No hardware decoder is available; use the platform software codec under YCore scheduling"
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

        val fallbackHdrType = selectedRequirement?.hdrType ?: request.video.hdrType
        val displayCanPresentFallback = capabilities.supportsDisplayHdr(fallbackHdrType)
        val softwareVideo = selectedDecoder == null || !displayCanPresentFallback
        val softwareVideoToneMap =
            softwareVideo &&
                request.enhancedDemuxSupported &&
                !request.video.secureDecodeRequired &&
                fallbackHdrType.supportsOwnedSoftwareToneMap()
        return YPlaybackPlan(
            route = YPlaybackRoute.SoftwareFallback,
            demuxPath = if (request.enhancedDemuxSupported) YDemuxPath.Enhanced else YDemuxPath.Software,
            decodePath =
                when {
                    softwareVideo -> YDecodePath.Software
                    selectedDecoder?.hardwareAccelerated == true -> YDecodePath.Hardware
                    else -> YDecodePath.PlatformSoftware
                },
            renderPath = if (softwareVideo) YRenderPath.Gpu else YRenderPath.SurfaceDirect,
            outputHdrType =
                if (softwareVideo) YHdrType.Sdr else fallbackHdrType,
            decoderName = selectedDecoder?.name?.takeUnless { softwareVideo },
            nativeAudio = request.audio == null || request.enhancedDemuxSupported,
            audioPath = if (request.audio == null) YAudioOutputPath.None else YAudioOutputPath.DecodePcm,
            softwareAudioDecode = request.audio != null && !nativeAudio && request.enhancedDemuxSupported,
            softwareVideoToneMap = softwareVideoToneMap,
            usesHdrFallback = usesHdrFallback,
            reason =
                when {
                    softwareVideoToneMap ->
                        "Decode $fallbackHdrType and tone-map to SDR inside YCore"
                    !nativeAudio && request.enhancedDemuxSupported ->
                        "Selected audio codec has no platform decoder; use FFmpeg PCM software decode"
                    !nativeAudio ->
                        "Selected audio codec has no executable decode path"
                    else ->
                        "No compatible platform video decoder exists; use FFmpeg software video decode"
                },
        )
    }
}

private fun YHdrType.supportsOwnedSoftwareToneMap(): Boolean = this in setOf(YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.Hlg)
