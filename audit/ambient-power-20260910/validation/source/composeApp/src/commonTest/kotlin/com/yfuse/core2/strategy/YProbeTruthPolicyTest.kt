package com.yfuse.core2.strategy

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YProbeTruthPolicyTest {
    @Test
    fun unknown_audio_and_risky_container_metadata_request_ffmpeg_truth() {
        assertTrue(
            shouldRequestEnhancedProbe(
                container = YContainer.Mp4,
                videoCodec = YVideoCodec.H264,
                audioCodec = YAudioCodec.Unknown,
            ),
        )
        assertTrue(
            shouldRequestEnhancedProbe(
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.H264,
                audioCodec = null,
            ),
        )
        assertFalse(
            shouldRequestEnhancedProbe(
                container = YContainer.Mp4,
                videoCodec = YVideoCodec.H264,
                audioCodec = YAudioCodec.Aac,
            ),
        )
    }

    @Test
    fun settled_matroska_hevc_metadata_does_not_request_ffmpeg_truth() {
        assertFalse(
            shouldRequestEnhancedProbe(
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.H265,
                audioCodec = YAudioCodec.Aac,
                hdrType = YHdrType.DolbyVision,
                bitDepth = 10,
            ),
        )
        assertFalse(
            shouldRequestEnhancedProbe(
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.H265,
                audioCodec = YAudioCodec.Eac3,
                hdrType = YHdrType.Hdr10,
                bitDepth = 10,
            ),
        )
        assertFalse(
            shouldRequestEnhancedProbe(
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.H265,
                audioCodec = YAudioCodec.Aac,
                hdrType = YHdrType.Sdr,
                bitDepth = 8,
            ),
        )
    }

    @Test
    fun unresolved_dynamic_range_and_transport_streams_still_request_ffmpeg_truth() {
        assertTrue(
            shouldRequestEnhancedProbe(
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.H265,
                audioCodec = YAudioCodec.Aac,
                hdrType = YHdrType.Sdr,
                bitDepth = 10,
            ),
        )
        assertTrue(
            shouldRequestEnhancedProbe(
                container = YContainer.Matroska,
                videoCodec = YVideoCodec.Av1,
                audioCodec = YAudioCodec.Opus,
                hdrType = YHdrType.Sdr,
                bitDepth = 8,
                hintedHighDynamicRange = true,
            ),
        )
        assertTrue(
            shouldRequestEnhancedProbe(
                container = YContainer.M2ts,
                videoCodec = YVideoCodec.H265,
                audioCodec = YAudioCodec.Ac3,
                hdrType = YHdrType.Hdr10,
                bitDepth = 10,
            ),
        )
    }

    @Test
    fun enhanced_audio_replaces_only_absent_or_unknown_platform_audio() {
        assertTrue(enhancedAudioCodecIsMoreReliable(YAudioCodec.Unknown, YAudioCodec.Aac))
        assertTrue(enhancedAudioCodecIsMoreReliable(null, YAudioCodec.Ac3))
        assertFalse(enhancedAudioCodecIsMoreReliable(YAudioCodec.Ac3, YAudioCodec.Aac))
        assertFalse(enhancedAudioCodecIsMoreReliable(YAudioCodec.Ac3, YAudioCodec.Unknown))
    }
}
