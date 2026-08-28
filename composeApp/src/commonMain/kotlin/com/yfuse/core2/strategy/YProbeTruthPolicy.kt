package com.yfuse.core2.strategy

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YVideoCodec

/** Decides when platform extractor metadata is insufficient for deterministic native routing. */
internal fun shouldRequestEnhancedProbe(
    container: YContainer,
    videoCodec: YVideoCodec,
    audioCodec: YAudioCodec?,
): Boolean {
    val containerNeedsTruth = container in ENHANCED_TRUTH_CONTAINERS
    val videoNeedsTruth =
        containerNeedsTruth && videoCodec in setOf(YVideoCodec.H265, YVideoCodec.Av1)
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
