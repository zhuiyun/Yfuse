package com.yfuse.core2.strategy

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability
import com.yfuse.core2.capability.YVideoRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YPlaybackStrategyTest {
    private val strategy = DefaultYPlaybackStrategy()

    @Test
    fun `platform Dolby Vision uses tunnel when the decoder and display support it`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Mp4,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                width = 3840,
                                height = 2160,
                                frameRate = 23.976f,
                                bitDepth = 10,
                                hdrType = YHdrType.DolbyVision,
                                dolbyVisionProfile = 8,
                            ),
                        platformDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(
                                    hdr = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                                    tunneled = true,
                                ),
                            ),
                        displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                    ),
            )

        assertEquals(YPlaybackRoute.NativeTunnel, plan.route)
        assertEquals(YRenderPath.Tunnel, plan.renderPath)
        assertEquals(YHdrType.DolbyVision, plan.outputHdrType)
        assertFalse(plan.usesHdrFallback)
    }

    @Test
    fun `Matroska Dolby Vision keeps hardware decode through enhanced demux`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                hdrType = YHdrType.DolbyVision,
                                bitDepth = 10,
                            ),
                        platformDemuxSupported = false,
                        enhancedDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(hdr = setOf(YHdrType.Sdr, YHdrType.DolbyVision)),
                            ),
                        displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                    ),
            )

        assertEquals(YPlaybackRoute.NativeEnhanced, plan.route)
        assertEquals(YDemuxPath.Enhanced, plan.demuxPath)
        assertEquals(YDecodePath.Hardware, plan.decodePath)
        assertEquals(YRenderPath.SurfaceDirect, plan.renderPath)
    }

    @Test
    fun `Dolby Vision profile 8 can fall back to HDR10 base layer`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                hdrType = YHdrType.DolbyVision,
                                dolbyVisionProfile = 8,
                                bitDepth = 10,
                            ),
                        platformDemuxSupported = false,
                        fallbackHdrType = YHdrType.Hdr10,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(hdr = setOf(YHdrType.Sdr, YHdrType.Hdr10)),
                            ),
                        displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.Hdr10),
                    ),
            )

        assertEquals(YPlaybackRoute.NativeEnhanced, plan.route)
        assertEquals(YHdrType.Hdr10, plan.outputHdrType)
        assertTrue(plan.usesHdrFallback)
    }

    @Test
    fun `unsupported hardware reaches universal software fallback`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Mov,
                        video = YVideoRequirement(codec = YVideoCodec.ProRes),
                        platformDemuxSupported = false,
                        enhancedDemuxSupported = true,
                    ),
                capabilities = YDeviceCapabilities.conservative(),
            )

        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
        assertEquals(YDecodePath.Software, plan.decodePath)
        assertEquals(YRenderPath.Gpu, plan.renderPath)
    }

    private fun decoder(
        hdr: Set<YHdrType>,
        tunneled: Boolean = false,
    ): YVideoDecoderCapability =
        YVideoDecoderCapability(
            name = "test.hevc.decoder",
            codec = YVideoCodec.H265,
            hdrTypes = hdr,
            maxWidth = 7680,
            maxHeight = 4320,
            maxFrameRate = 120.0,
            tunneledPlayback = tunneled,
            adaptivePlayback = true,
        )
}
