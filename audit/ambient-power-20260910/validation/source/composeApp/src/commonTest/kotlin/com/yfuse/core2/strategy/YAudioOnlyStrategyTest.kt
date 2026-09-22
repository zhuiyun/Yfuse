package com.yfuse.core2.strategy

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Music, audiobooks and audio-only versions have no video track.
 *
 * Requiring one rejected them at the container stage, which on a native-only artifact means they
 * are simply unplayable. The video decision tree has nothing to decide for them, so it is skipped
 * entirely rather than fed a synthesized requirement.
 */
class YAudioOnlyStrategyTest {
    private val strategy = DefaultYPlaybackStrategy()

    @Test
    fun `audio-only media plans a native direct route with no video pipeline`() {
        val plan = strategy.plan(request(), capabilities())

        assertEquals(YPlaybackRoute.NativeDirect, plan.route)
        assertEquals(YDemuxPath.Platform, plan.demuxPath)
        assertEquals(YRenderPath.SurfaceDirect, plan.renderPath)
        assertTrue(plan.nativeAudio)
        assertEquals(YAudioOutputPath.DecodePcm, plan.audioPath)
        // No video decoder is selected, so none can be blamed for a failure later.
        assertEquals(null, plan.decoderName)
        assertFalse(plan.usesHdrFallback)
    }

    @Test
    fun `the video placeholder never influences the plan`() {
        val plain = strategy.plan(request(), capabilities())
        val absurdVideo =
            strategy.plan(
                request().copy(
                    video =
                        YVideoRequirement(
                            codec = YVideoCodec.Vc1,
                            width = 7680,
                            height = 4320,
                            frameRate = 120f,
                            bitDepth = 12,
                            hdrType = YHdrType.DolbyVision,
                            dolbyVisionProfile = 7,
                            secureDecodeRequired = true,
                        ),
                ),
                capabilities(),
            )

        assertEquals(plain, absurdVideo)
    }

    @Test
    fun `passthrough capability is honoured for audio-only media`() {
        val plan =
            strategy.plan(
                request(codec = YAudioCodec.Eac3),
                capabilities(passthrough = setOf(YAudioCodec.Eac3)),
            )

        assertEquals(YAudioOutputPath.Passthrough, plan.audioPath)
        assertEquals(YPlaybackRoute.NativeDirect, plan.route)
    }

    @Test
    fun `a codec with no platform decoder is not claimed as executable`() {
        val plan = strategy.plan(request(codec = YAudioCodec.Eac3), capabilities())

        // The enhanced and software sessions are built around a video track, so an audio-only
        // source they cannot serve must fall back rather than fail at open time.
        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
        assertFalse(plan.nativeAudio)
    }

    @Test
    fun `a container the platform extractor cannot demux is not claimed as executable`() {
        val plan = strategy.plan(request().copy(platformDemuxSupported = false), capabilities())

        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
        assertFalse(plan.nativeAudio)
    }

    private fun request(codec: YAudioCodec = YAudioCodec.Aac) =
        YPlaybackRequest(
            container = YContainer.Mp4,
            video =
                YVideoRequirement(
                    codec = YVideoCodec.Unknown,
                    width = 0,
                    height = 0,
                    surfaceOutputRequired = false,
                ),
            audio = YAudioRequirement(codec = codec, channelCount = 2, sampleRate = 48_000),
            audioOnly = true,
            platformDemuxSupported = true,
            enhancedDemuxSupported = true,
            preferTunnel = false,
        )

    private fun capabilities(passthrough: Set<YAudioCodec> = emptySet()) =
        YDeviceCapabilities(
            videoDecoders = emptyList(),
            audioDecoders = setOf(YAudioCodec.Aac),
            audioPassthrough = passthrough,
            displayHdrTypes = setOf(YHdrType.Sdr),
        )
}
