package com.yfuse.core2.dolby

import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement

enum class YDolbyVisionUnsupportedReason {
    UnsupportedProfileFamily,
    MissingExactProfileDecoder,
    MissingDolbyVisionDisplay,
    MissingCompatibleBaseDecoder,
    MissingCompatibleBaseDisplay,
    NoSafeFallback,
}

sealed interface YDolbyVisionRouteDecision {
    data class Native(
        val decoderName: String,
        val profile: Int,
        val codec: YVideoCodec,
        /** True only when an independent output trace proved Profile-7 EL composition. */
        val canClaimFelComposition: Boolean,
    ) : YDolbyVisionRouteDecision

    data class CompatibleBase(
        val decoderName: String,
        val hdrType: YHdrType,
        val codec: YVideoCodec,
    ) : YDolbyVisionRouteDecision

    data class Unsupported(
        val reason: YDolbyVisionUnsupportedReason,
    ) : YDolbyVisionRouteDecision
}

/**
 * Dolby-specific routing kept outside the generic playback planner.
 *
 * The generic planner answers whether a codec/HDR route exists. This router adds the Dolby rules
 * that must never be inferred from a generic HEVC capability: exact semantic profile matching,
 * P8/P10 compatibility-id fallback, and Profile-7 FEL evidence discipline.
 */
object YDolbyVisionRouter {
    fun decide(
        video: YVideoRequirement,
        evidence: YDolbyVisionStreamEvidence,
        capabilities: YDeviceCapabilities,
        outputEvidence: YDolbyVisionOutputEvidence? = null,
    ): YDolbyVisionRouteDecision {
        val config = evidence.config
        val codec = config.codecFamily.toVideoCodec()
            ?: return YDolbyVisionRouteDecision.Unsupported(
                YDolbyVisionUnsupportedReason.UnsupportedProfileFamily,
            )
        val exactRequirement =
            video.copy(
                codec = codec,
                bitDepth = maxOf(video.bitDepth, 10),
                hdrType = YHdrType.DolbyVision,
                dolbyVisionProfile = config.profile,
            )
        val exactDecoder = capabilities.bestDecoder(exactRequirement)
        if (exactDecoder != null && capabilities.supportsDisplayHdr(YHdrType.DolbyVision)) {
            return YDolbyVisionRouteDecision.Native(
                decoderName = exactDecoder.name,
                profile = config.profile,
                codec = codec,
                canClaimFelComposition =
                    config.profile == 7 &&
                        outputEvidence?.canClaimFELComposition == true,
            )
        }

        val compatibleHdr = config.compatibleBaseHdr
        if (compatibleHdr != null) {
            val fallbackRequirement =
                exactRequirement.copy(
                    hdrType = compatibleHdr,
                    dolbyVisionProfile = null,
                )
            val fallbackDecoder = capabilities.bestDecoder(fallbackRequirement)
            if (fallbackDecoder == null) {
                return YDolbyVisionRouteDecision.Unsupported(
                    YDolbyVisionUnsupportedReason.MissingCompatibleBaseDecoder,
                )
            }
            if (!capabilities.supportsDisplayHdr(compatibleHdr)) {
                return YDolbyVisionRouteDecision.Unsupported(
                    YDolbyVisionUnsupportedReason.MissingCompatibleBaseDisplay,
                )
            }
            return YDolbyVisionRouteDecision.CompatibleBase(
                decoderName = fallbackDecoder.name,
                hdrType = compatibleHdr,
                codec = codec,
            )
        }

        return YDolbyVisionRouteDecision.Unsupported(
            when {
                exactDecoder == null -> YDolbyVisionUnsupportedReason.MissingExactProfileDecoder
                !capabilities.supportsDisplayHdr(YHdrType.DolbyVision) ->
                    YDolbyVisionUnsupportedReason.MissingDolbyVisionDisplay
                else -> YDolbyVisionUnsupportedReason.NoSafeFallback
            },
        )
    }
}

private fun YDolbyVisionCodecFamily.toVideoCodec(): YVideoCodec? =
    when (this) {
        YDolbyVisionCodecFamily.Hevc -> YVideoCodec.H265
        YDolbyVisionCodecFamily.Avc -> YVideoCodec.H264
        YDolbyVisionCodecFamily.Av1 -> YVideoCodec.Av1
        YDolbyVisionCodecFamily.Unknown -> null
    }
