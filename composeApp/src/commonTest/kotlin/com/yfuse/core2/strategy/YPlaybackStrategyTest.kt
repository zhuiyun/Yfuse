package com.yfuse.core2.strategy

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
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
    fun `platform Dolby Vision uses tunnel when video display and audio clock are supported`() {
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
                        audio = YAudioRequirement(codec = YAudioCodec.Aac),
                        platformDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(
                                    hdr = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(8),
                                    tunneled = true,
                                ),
                            ),
                        audioDecoders = setOf(YAudioCodec.Aac),
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
                                dolbyVisionProfile = 8,
                            ),
                        platformDemuxSupported = false,
                        enhancedDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(
                                    hdr = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(8),
                                ),
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
    fun `wrong Dolby profile never qualifies the native decoder`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Mp4,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                hdrType = YHdrType.DolbyVision,
                                bitDepth = 10,
                                dolbyVisionProfile = 7,
                            ),
                        platformDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(
                                    hdr = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(5, 8),
                                ),
                            ),
                        displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                    ),
            )

        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
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
                                decoder(
                                    hdr = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(8),
                                ),
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
    fun `Profile 7 prefers its compatible base for GPU presentation without native Dolby output`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                hdrType = YHdrType.DolbyVision,
                                dolbyVisionProfile = 7,
                                bitDepth = 10,
                            ),
                        platformDemuxSupported = false,
                        fallbackHdrType = YHdrType.Hdr10,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(
                                    hdr = setOf(YHdrType.Sdr, YHdrType.Hdr10, YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(7),
                                ),
                            ),
                        displayHdrTypes = setOf(YHdrType.Sdr),
                    ),
            )

        assertEquals(YPlaybackRoute.GpuEnhanced, plan.route)
        assertEquals(YHdrType.Hdr10, plan.inputHdrType)
        assertEquals(YHdrType.Sdr, plan.outputHdrType)
        assertTrue(plan.usesHdrFallback)
    }

    @Test
    fun `HDR10 hardware decode without an HDR display uses GPU tone mapping`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                width = 3840,
                                height = 2160,
                                frameRate = 24f,
                                bitDepth = 10,
                                hdrType = YHdrType.Hdr10,
                            ),
                        platformDemuxSupported = false,
                        enhancedDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders = listOf(decoder(hdr = setOf(YHdrType.Sdr, YHdrType.Hdr10))),
                        displayHdrTypes = setOf(YHdrType.Sdr),
                    ),
            )

        assertEquals(YPlaybackRoute.GpuEnhanced, plan.route)
        assertEquals(YDecodePath.Hardware, plan.decodePath)
        assertEquals(YRenderPath.Gpu, plan.renderPath)
        assertEquals(YHdrType.Sdr, plan.outputHdrType)
        assertFalse(plan.softwareVideoToneMap)
    }

    @Test
    fun `secure HDR never enters software tone mapping`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Mp4,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                hdrType = YHdrType.Hdr10,
                                secureDecodeRequired = true,
                            ),
                        platformDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(hdr = setOf(YHdrType.Sdr, YHdrType.Hdr10)).copy(
                                    securePlayback = true,
                                ),
                            ),
                        displayHdrTypes = setOf(YHdrType.Sdr),
                    ),
            )

        assertFalse(plan.softwareVideoToneMap)
    }

    @Test
    fun `unsupported audio prevents a native video route from becoming silent playback`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Mp4,
                        video = YVideoRequirement(codec = YVideoCodec.H265),
                        audio = YAudioRequirement(codec = YAudioCodec.DtsHd, channelCount = 8),
                        platformDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders = listOf(decoder(hdr = setOf(YHdrType.Sdr))),
                        audioDecoders = setOf(YAudioCodec.Aac, YAudioCodec.Ac3),
                    ),
            )

        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
        assertTrue(plan.nativeAudio)
        assertTrue(plan.softwareAudioDecode)
        assertEquals(YAudioOutputPath.DecodePcm, plan.audioPath)
        assertTrue("audio" in plan.reason.lowercase())
    }

    @Test
    fun `TrueHD Atmos uses encoded passthrough when the active output proves support`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video = YVideoRequirement(codec = YVideoCodec.H265),
                        audio = YAudioRequirement(codec = YAudioCodec.TrueHdAtmos, channelCount = 8),
                        platformDemuxSupported = false,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders = listOf(decoder(hdr = setOf(YHdrType.Sdr))),
                        audioPassthrough = setOf(YAudioCodec.TrueHdAtmos),
                    ),
            )

        assertEquals(YPlaybackRoute.NativeEnhanced, plan.route)
        assertEquals(YAudioOutputPath.Passthrough, plan.audioPath)
        assertTrue(plan.nativeAudio)
    }

    @Test
    fun `disabled passthrough cannot make a route depend on the encoded output`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video = YVideoRequirement(codec = YVideoCodec.H265),
                        audio = YAudioRequirement(codec = YAudioCodec.TrueHdAtmos, channelCount = 8),
                        platformDemuxSupported = false,
                        allowAudioPassthrough = false,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders = listOf(decoder(hdr = setOf(YHdrType.Sdr))),
                        audioPassthrough = setOf(YAudioCodec.TrueHdAtmos),
                    ),
            )

        assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
        assertEquals(YAudioOutputPath.DecodePcm, plan.audioPath)
        assertTrue(plan.nativeAudio)
        assertTrue(plan.softwareAudioDecode)
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

    @Test
    fun `platform software decoder stays inside YCore and never tunnels`() {
        val plan =
            strategy.plan(
                request =
                    YPlaybackRequest(
                        container = YContainer.WebM,
                        video = YVideoRequirement(codec = YVideoCodec.Av1),
                        platformDemuxSupported = true,
                    ),
                capabilities =
                    YDeviceCapabilities(
                        videoDecoders =
                            listOf(
                                decoder(
                                    hdr = setOf(YHdrType.Sdr),
                                    codec = YVideoCodec.Av1,
                                    hardwareAccelerated = false,
                                    tunneled = false,
                                ),
                            ),
                    ),
            )

        assertEquals(YPlaybackRoute.NativeDirect, plan.route)
        assertEquals(YDecodePath.PlatformSoftware, plan.decodePath)
        assertEquals(YRenderPath.SurfaceDirect, plan.renderPath)
    }

    private fun decoder(
        hdr: Set<YHdrType>,
        dolbyProfiles: Set<Int> = emptySet(),
        tunneled: Boolean = false,
        codec: YVideoCodec = YVideoCodec.H265,
        hardwareAccelerated: Boolean = true,
    ): YVideoDecoderCapability =
        YVideoDecoderCapability(
            name = "test.hevc.decoder",
            codec = codec,
            hardwareAccelerated = hardwareAccelerated,
            hdrTypes = hdr,
            dolbyVisionProfiles = dolbyProfiles,
            maxWidth = 7680,
            maxHeight = 4320,
            maxFrameRate = 120.0,
            maxBitDepth = 10,
            tunneledPlayback = tunneled,
            adaptivePlayback = true,
        )
}
