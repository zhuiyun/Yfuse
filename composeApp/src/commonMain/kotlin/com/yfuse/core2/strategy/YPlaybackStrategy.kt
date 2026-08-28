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

/** User intent translated at the product/Core2 boundary. */
enum class YDecoderPreference {
    HardwarePreferred,
    Software,
    Automatic,
}

/** Product playback policy that must remain visible to Core2's own route planner. */
enum class YOptimizationPreference {
    Balanced,
    PowerSaver,
    Quality,
    Compatibility,
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
    val decoderPreference: YDecoderPreference = YDecoderPreference.Automatic,
    val optimizationPreference: YOptimizationPreference = YOptimizationPreference.Balanced,
)

data class YPlaybackPlan(
    val route: YPlaybackRoute,
    val demuxPath: YDemuxPath,
    val decodePath: YDecodePath,
    val renderPath: YRenderPath,
    val outputHdrType: YHdrType,
    /** Dynamic range entering the selected decoder/render pipeline before output conversion. */
    val inputHdrType: YHdrType = outputHdrType,
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
                request.optimizationPreference == YOptimizationPreference.Compatibility &&
                    request.enhancedDemuxSupported -> YDemuxPath.Enhanced
                request.platformDemuxSupported -> YDemuxPath.Platform
                request.enhancedDemuxSupported -> YDemuxPath.Enhanced
                else -> null
            }

        // DRM/secure video cannot leave the protected platform decoder path. In that one case the
        // user's software preference is safely overridden instead of creating an impossible route.
        val forceSoftware =
            request.decoderPreference == YDecoderPreference.Software &&
                !request.video.secureDecodeRequired
        val effectiveDecoderPreference =
            if (
                request.video.secureDecodeRequired &&
                request.decoderPreference == YDecoderPreference.Software
            ) {
                YDecoderPreference.HardwarePreferred
            } else {
                request.decoderPreference
            }
        val originalDecoder =
            if (forceSoftware) {
                null
            } else {
                capabilities.preferredDecoder(request.video, effectiveDecoderPreference)
            }
        val fallbackRequirement =
            request.fallbackHdrType
                ?.takeIf { it != request.video.hdrType }
                ?.let {
                    request.video.copy(
                        hdrType = it,
                        dolbyVisionProfile = null,
                    )
                }
        val fallbackDecoder =
            if (forceSoftware) {
                null
            } else {
                fallbackRequirement?.let { capabilities.preferredDecoder(it, effectiveDecoderPreference) }
            }
        val originalNativeOutput =
            originalDecoder != null && capabilities.supportsDisplayHdr(request.video.hdrType)
        val fallbackNativeOutput =
            fallbackDecoder != null &&
                capabilities.supportsDisplayHdr(requireNotNull(fallbackRequirement).hdrType)
        val usesHdrFallback =
            when {
                originalNativeOutput -> false
                request.optimizationPreference == YOptimizationPreference.Quality &&
                    originalDecoder != null -> false
                fallbackNativeOutput -> true
                request.video.dolbyVisionProfile == 7 && fallbackDecoder != null -> true
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
                inputHdrType = selectedRequirement.hdrType,
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
            inputHdrType = fallbackHdrType,
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

private fun YDeviceCapabilities.preferredDecoder(
    requirement: YVideoRequirement,
    preference: YDecoderPreference,
) =
    when (preference) {
        YDecoderPreference.HardwarePreferred ->
            videoDecoders
                .asSequence()
                .filter { it.supports(requirement) }
                .filter { it.hardwareAccelerated }
                .sortedWith(
                    compareByDescending<com.yfuse.core2.capability.YVideoDecoderCapability> {
                        it.tunneledPlayback
                    }.thenByDescending { it.adaptivePlayback },
                ).firstOrNull()
                ?: bestDecoder(requirement)
        YDecoderPreference.Automatic -> bestDecoder(requirement)
        YDecoderPreference.Software -> null
    }

private fun YHdrType.supportsOwnedSoftwareToneMap(): Boolean = this in setOf(YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.Hlg)
