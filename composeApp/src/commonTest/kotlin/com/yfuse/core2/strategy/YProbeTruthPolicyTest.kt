package com.yfuse.core2.strategy

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
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
    fun enhanced_audio_replaces_only_absent_or_unknown_platform_audio() {
        assertTrue(enhancedAudioCodecIsMoreReliable(YAudioCodec.Unknown, YAudioCodec.Aac))
        assertTrue(enhancedAudioCodecIsMoreReliable(null, YAudioCodec.Ac3))
        assertFalse(enhancedAudioCodecIsMoreReliable(YAudioCodec.Ac3, YAudioCodec.Aac))
        assertFalse(enhancedAudioCodecIsMoreReliable(YAudioCodec.Ac3, YAudioCodec.Unknown))
    }
}
