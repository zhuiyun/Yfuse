package com.yfuse.core2.strategy

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec

/**
 * Decides when platform extractor metadata is insufficient for deterministic native routing.
 *
 * The FFmpeg truth probe opens the source a second time over the network and costs several
 * seconds of startup, so it is requested only for facts the platform extractor did not settle:
 * an absent or unknown audio track, transport streams (whose platform extractor exposes almost
 * no stream metadata), and Matroska HEVC/AV1 whose dynamic range looks unresolved - a 10-bit
 * stream reported as SDR, or a source the server labels HDR that the extractor calls SDR. A
 * Matroska stream with known audio and a settled HDR type is routed from platform facts alone.
 */
internal fun shouldRequestEnhancedProbe(
    container: YContainer,
    videoCodec: YVideoCodec,
    audioCodec: YAudioCodec?,
    hdrType: YHdrType = YHdrType.Sdr,
    bitDepth: Int = 8,
    hintedHighDynamicRange: Boolean = false,
): Boolean {
    val containerNeedsTruth = container in ENHANCED_TRUTH_CONTAINERS
    val advancedVideo = videoCodec in setOf(YVideoCodec.H265, YVideoCodec.Av1)
    val transportStreamNeedsTruth = container in TRANSPORT_STREAM_CONTAINERS && advancedVideo
    val dynamicRangeUnresolved = hdrType == YHdrType.Sdr && (bitDepth > 8 || hintedHighDynamicRange)
    val videoNeedsTruth =
        transportStreamNeedsTruth || (containerNeedsTruth && advancedVideo && dynamicRangeUnresolved)
    val audioNeedsTruth =
        audioCodec == YAudioCodec.Unknown || (audioCodec == null && containerNeedsTruth)
    return videoNeedsTruth || audioNeedsTruth
}

/** A known FFmpeg codec may replace absent/unknown platform audio, never another known codec. */
internal fun enhancedAudioCodecIsMoreReliable(
    platformCodec: YAudioCodec?,
    enhancedCodec: YAudioCodec?,
): Boolean =
    enhancedCodec != null &&
        enhancedCodec != YAudioCodec.Unknown &&
        (platformCodec == null || platformCodec == YAudioCodec.Unknown)

private val ENHANCED_TRUTH_CONTAINERS =
    setOf(
        YContainer.Matroska,
        YContainer.MpegTs,
        YContainer.M2ts,
    )

private val TRANSPORT_STREAM_CONTAINERS =
    setOf(
        YContainer.MpegTs,
        YContainer.M2ts,
    )
