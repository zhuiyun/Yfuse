package com.yfuse.core2.strategy

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability
import com.yfuse.core2.capability.YVideoRequirement
import kotlin.test.Test
import kotlin.test.assertEquals

class YPlatformAudioDemuxStrategyTest {
    private val strategy = DefaultYPlaybackStrategy()
    private val capabilities =
        YDeviceCapabilities(
            videoDecoders =
                listOf(
                    YVideoDecoderCapability(
                        name = "test.hevc.decoder",
                        codec = YVideoCodec.H265,
                        hardwareAccelerated = true,
                        hdrTypes = setOf(YHdrType.Sdr),
                        maxWidth = 7680,
                        maxHeight = 4320,
                        maxFrameRate = 120.0,
                        maxBitDepth = 10,
                        tunneledPlayback = false,
                        adaptivePlayback = true,
                    ),
                ),
            audioDecoders = setOf(YAudioCodec.Ac3),
            displayHdrTypes = setOf(YHdrType.Sdr),
        )

    @Test
    fun platformVideoWithHiddenAudioUsesEnhancedDemux() {
        val plan =
            strategy.plan(
                request = request(platformAudioDemuxSupported = false),
                capabilities = capabilities,
            )

        assertEquals(YPlaybackRoute.NativeEnhanced, plan.route)
        assertEquals(YDemuxPath.Enhanced, plan.demuxPath)
    }

    @Test
    fun platformVideoWithExposedAudioKeepsPlatformDemux() {
        val plan =
            strategy.plan(
                request = request(platformAudioDemuxSupported = true),
                capabilities = capabilities,
            )

        assertEquals(YPlaybackRoute.NativeDirect, plan.route)
        assertEquals(YDemuxPath.Platform, plan.demuxPath)
    }

    @Test
    fun platformVideoWithNoAudioButServerDeclaredAudioUsesEnhancedDemux() {
        // MediaExtractor hid the only audio track (an unmapped Matroska CodecID). NativeDirect
        // would open the container and refuse it at Demux, so the planner must not choose it.
        val plan =
            strategy.plan(
                request = request(platformAudioDemuxSupported = true, audio = null, sourceDeclaresAudio = true),
                capabilities = capabilities,
            )

        assertEquals(YPlaybackRoute.NativeEnhanced, plan.route)
        assertEquals(YDemuxPath.Enhanced, plan.demuxPath)
    }

    @Test
    fun platformVideoWithNoAudioAndNoServerAudioStaysOnPlatformDemux() {
        val plan =
            strategy.plan(
                request = request(platformAudioDemuxSupported = true, audio = null, sourceDeclaresAudio = false),
                capabilities = capabilities,
            )

        assertEquals(YPlaybackRoute.NativeDirect, plan.route)
        assertEquals(YDemuxPath.Platform, plan.demuxPath)
    }

    private fun request(
        platformAudioDemuxSupported: Boolean,
        audio: YAudioRequirement? =
            YAudioRequirement(
                codec = YAudioCodec.Ac3,
                channelCount = 6,
                sampleRate = 48_000,
            ),
        sourceDeclaresAudio: Boolean = audio != null,
    ): YPlaybackRequest =
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
            audio = audio,
            platformDemuxSupported = true,
            platformAudioDemuxSupported = platformAudioDemuxSupported,
            enhancedDemuxSupported = true,
            preferTunnel = false,
            sourceDeclaresAudio = sourceDeclaresAudio,
        )
}
