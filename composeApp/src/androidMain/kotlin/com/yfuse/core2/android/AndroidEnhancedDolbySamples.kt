package com.yfuse.core2.android

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionCodecFamily
import com.yfuse.core2.dolby.convertDolbyVisionProfile7To8Sample
import com.yfuse.core2.dolby.dolbyVisionHevcBaseLayerSample
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YPlaybackPlan

/**
 * True only when the source track actually carries Dolby Vision metadata and the plan strips it
 * to a compatible HDR10 / SDR / HLG base layer. Non-Dolby HDR10+ -> HDR10 fallback stays false.
 */
internal fun YPlaybackPlan.extractsDolbyBaseLayerFor(source: YVideoTrackFormat?): Boolean =
    source?.dolbyVisionConfig != null && extractsDolbyBaseLayer

internal fun resolveEffectiveEnhancedVideoTrack(
    source: YVideoTrackFormat,
    plan: YPlaybackPlan,
): YVideoTrackFormat {
    if (plan.convertsDolbyProfile7To8) {
        val config =
            requireNotNull(source.dolbyVisionConfig) {
                "Profile 7 -> 8.1 conversion requires Dolby Vision configuration"
            }
        require(config.profile == 7 && source.codec == YVideoCodec.H265) {
            "Profile 7 -> 8.1 conversion requires HEVC Dolby Vision Profile 7"
        }
        return source.copy(
            mimeType = "video/dolby-vision",
            hdrType = YHdrType.DolbyVision,
            samplePacking = YSamplePacking.AnnexB,
            dolbyVisionConfig = config.toProfile81Config(),
        )
    }
    if (!plan.usesHdrFallback) return source
    val config = source.dolbyVisionConfig
    // Non-Dolby HDR fallback (e.g., HDR10+ presented as compatible HDR10).
    if (config == null) {
        return source.copy(hdrType = plan.inputHdrType)
    }
    if (plan.decodePath == YDecodePath.Software && plan.inputHdrType == YHdrType.DolbyVision) {
        require(
            yCoreSoftwarePlanPassesDolbyGuard(plan, config) &&
                source.codec == YVideoCodec.H265 &&
                source.samplePacking != null,
        ) {
            "Profile 5 SDR conversion needs single-layer HEVC with identified packet packing and RPU"
        }
        return source
    }
    require(config.profile in setOf(7, 8, 10) && config.compatibleBaseHdr == plan.inputHdrType) {
        "Only explicit Profile 7/8/10 compatible-base fallback is supported"
    }
    return when (source.codec) {
        YVideoCodec.H265 ->
            source.copy(
                mimeType = "video/hevc",
                hdrType = plan.inputHdrType,
                samplePacking = YSamplePacking.AnnexB,
                dolbyVisionConfig = null,
            )
        YVideoCodec.Av1 -> {
            require(config.profile == 10) { "AV1 Dolby Vision compatible base requires Profile 10" }
            source.copy(
                mimeType = "video/av01",
                hdrType = plan.inputHdrType,
                dolbyVisionConfig = null,
            )
        }
        else -> throw IllegalArgumentException("Dolby Vision compatible base requires HEVC or AV1")
    }
}

internal fun transformEnhancedVideoSample(
    data: ByteArray,
    sourceTrack: YVideoTrackFormat,
    currentPlan: YPlaybackPlan,
    softwareVideoActive: Boolean,
): ByteArray {
    if (currentPlan.convertsDolbyProfile7To8) {
        val packing =
            requireNotNull(sourceTrack.samplePacking) {
                "Dolby Vision Profile 7 -> 8.1 conversion requires known NAL packing"
            }
        return convertDolbyVisionProfile7To8Sample(data, packing, YSamplePacking.AnnexB)
    }
    if (currentPlan.extractsDolbyBaseLayerFor(sourceTrack)) {
        val config =
            requireNotNull(sourceTrack.dolbyVisionConfig) {
                "HDR fallback requires explicit Dolby Vision configuration"
            }
        require(config.compatibleBaseHdr == currentPlan.inputHdrType) {
            "Dolby compatible base does not match the selected pipeline input HDR route"
        }
        return when (config.codecFamily) {
            YDolbyVisionCodecFamily.Hevc -> {
                require(config.profile in setOf(7, 8)) {
                    "Only HEVC Dolby Vision Profile 7/8 compatible-base fallback is executable"
                }
                val packing =
                    requireNotNull(sourceTrack.samplePacking) {
                        "Dolby HEVC fallback requires known NAL packing"
                    }
                val decoderPacking = if (softwareVideoActive) packing else YSamplePacking.AnnexB
                dolbyVisionHevcBaseLayerSample(data, packing, decoderPacking)
            }
            YDolbyVisionCodecFamily.Av1 -> {
                require(config.profile == 10) {
                    "Only AV1 Dolby Vision Profile 10 compatible-base fallback is executable"
                }
                if (softwareVideoActive) data else YBitstream.normalizeAv1LowOverhead(data)
            }
            else -> throw IllegalArgumentException("Unsupported Dolby Vision compatible-base codec family")
        }
    }
    return normalizeVideoSampleForMediaCodec(data, sourceTrack)
}
