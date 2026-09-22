package com.yfuse.core2.android

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YPlaybackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2ProbeTruthMergeTest {
    @Test
    fun enhancedAudioTruthPreservesSuccessfulPlatformDemux() {
        val platform = probe(platformDemux = true, audioCodec = YAudioCodec.Unknown)
        val enhanced = probe(platformDemux = false, audioCodec = YAudioCodec.Ac3)

        val combined = enhanced.preservingPlatformDemuxCapability(platform)

        assertTrue(combined.playbackRequest.platformDemuxSupported)
        assertTrue(combined.playbackRequest.platformAudioDemuxSupported)
        assertTrue(combined.playbackRequest.enhancedDemuxSupported)
        assertEquals(YAudioCodec.Ac3, combined.playbackRequest.audio?.codec)
    }

    @Test
    fun platformMissedAudioKeepsVideoCapabilityButForcesEnhancedAudioDemux() {
        val platform = probe(platformDemux = true, audioCodec = null)
        val enhanced = probe(platformDemux = false, audioCodec = YAudioCodec.Ac3)

        val combined = enhanced.preservingPlatformDemuxCapability(platform)

        assertTrue(combined.playbackRequest.platformDemuxSupported)
        assertFalse(combined.playbackRequest.platformAudioDemuxSupported)
        assertTrue(combined.playbackRequest.enhancedDemuxSupported)
        assertEquals(YAudioCodec.Ac3, combined.playbackRequest.audio?.codec)
    }

    @Test
    fun missingPlatformAudioRequiresEnhancedTruthProbe() {
        val platform = probe(platformDemux = true, audioCodec = null)

        assertTrue(platform.requiresEnhancedTruthProbe())
    }

    @Test
    fun standaloneEnhancedProbeDoesNotInventPlatformDemux() {
        val enhanced = probe(platformDemux = false, audioCodec = YAudioCodec.Ac3)

        assertFalse(enhanced.playbackRequest.platformDemuxSupported)
        assertFalse(enhanced.playbackRequest.platformAudioDemuxSupported)
    }

    @Test
    fun cronetIsSkippedOnAffectedAndroidNineRuntime() {
        assertFalse(shouldAttemptCronetMediaTransport(androidApi = 28))
        assertTrue(shouldAttemptCronetMediaTransport(androidApi = 29))
    }

    @Test
    fun missingDeclaredAudioBypassesInconclusivePlatformRetry() {
        val platform = probe(platformDemux = true, audioCodec = null)
        assertTrue(platform.requiresEnhancedAudioDemux(sourceAudioTrackCount = 1))
        assertFalse(platform.requiresEnhancedAudioDemux(sourceAudioTrackCount = 0))
        assertFalse(probe(true, YAudioCodec.Ac3).requiresEnhancedAudioDemux(1))
        assertTrue(probe(false, YAudioCodec.Ac3).requiresEnhancedAudioDemux(1))
    }

    private fun probe(
        platformDemux: Boolean,
        audioCodec: YAudioCodec?,
    ): YCore2ProbeResult.Success =
        YCore2ProbeResult.Success(
            playbackRequest =
                YPlaybackRequest(
                    container = YContainer.Matroska,
                    video =
                        YVideoRequirement(
                            codec = YVideoCodec.H265,
                            width = 3840,
                            height = 1744,
                            frameRate = 24f,
                            bitDepth = 10,
                            hdrType = YHdrType.Sdr,
                        ),
                    audio =
                        audioCodec?.let { codec ->
                            YAudioRequirement(
                                codec = codec,
                                channelCount = 6,
                                sampleRate = 48_000,
                            )
                        },
                    platformDemuxSupported = platformDemux,
                    enhancedDemuxSupported = true,
                ),
            videoMime = "video/hevc",
            audioMime =
                when (audioCodec) {
                    null -> null
                    YAudioCodec.Unknown -> "audio/unknown"
                    else -> "audio/ac3"
                },
            durationMs = 6_211_163L,
        )
}
