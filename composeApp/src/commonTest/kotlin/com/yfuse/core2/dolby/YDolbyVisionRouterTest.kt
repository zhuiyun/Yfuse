package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability
import com.yfuse.core2.capability.YVideoRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class YDolbyVisionRouterTest {
    @Test
    fun `P8 native route requires exact P8 declaration`() {
        val decision =
            YDolbyVisionRouter.decide(
                video = video(YVideoCodec.H265, profile = 8),
                evidence = evidence(profile = 8, compatibilityId = 1),
                capabilities =
                    capabilities(
                        decoders =
                            listOf(
                                decoder(
                                    codec = YVideoCodec.H265,
                                    hdr = setOf(YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(8),
                                ),
                            ),
                        display = setOf(YHdrType.DolbyVision),
                    ),
            )

        val native = assertIs<YDolbyVisionRouteDecision.Native>(decision)
        assertEquals(8, native.profile)
        assertEquals(YVideoCodec.H265, native.codec)
        assertFalse(native.canClaimFelComposition)
    }

    @Test
    fun `P8 point 1 uses HDR10 compatible base when native P8 is unavailable`() {
        val decision =
            YDolbyVisionRouter.decide(
                video = video(YVideoCodec.H265, profile = 8),
                evidence = evidence(profile = 8, compatibilityId = 1),
                capabilities =
                    capabilities(
                        decoders =
                            listOf(
                                decoder(
                                    codec = YVideoCodec.H265,
                                    hdr = setOf(YHdrType.Hdr10),
                                ),
                            ),
                        display = setOf(YHdrType.Hdr10),
                    ),
            )

        val fallback = assertIs<YDolbyVisionRouteDecision.CompatibleBase>(decision)
        assertEquals(YHdrType.Hdr10, fallback.hdrType)
        assertEquals(YVideoCodec.H265, fallback.codec)
    }

    @Test
    fun `P7 never invents a compatible-base route`() {
        val decision =
            YDolbyVisionRouter.decide(
                video = video(YVideoCodec.H265, profile = 7),
                evidence = evidence(profile = 7, compatibilityId = 1, el = true),
                capabilities =
                    capabilities(
                        decoders =
                            listOf(
                                decoder(
                                    codec = YVideoCodec.H265,
                                    hdr = setOf(YHdrType.Hdr10),
                                ),
                            ),
                        display = setOf(YHdrType.Hdr10),
                    ),
            )

        assertIs<YDolbyVisionRouteDecision.Unsupported>(decision)
    }

    @Test
    fun `P7 native playback still cannot claim FEL without independent output evidence`() {
        val stream = evidence(profile = 7, el = true)
        val caps =
            capabilities(
                decoders =
                    listOf(
                        decoder(
                            codec = YVideoCodec.H265,
                            hdr = setOf(YHdrType.DolbyVision),
                            dolbyProfiles = setOf(7),
                        ),
                    ),
                display = setOf(YHdrType.DolbyVision),
            )

        val unmeasured =
            assertIs<YDolbyVisionRouteDecision.Native>(
                YDolbyVisionRouter.decide(
                    video = video(YVideoCodec.H265, profile = 7),
                    evidence = stream,
                    capabilities = caps,
                ),
            )
        assertFalse(unmeasured.canClaimFelComposition)

        val measured =
            assertIs<YDolbyVisionRouteDecision.Native>(
                YDolbyVisionRouter.decide(
                    video = video(YVideoCodec.H265, profile = 7),
                    evidence = stream,
                    capabilities = caps,
                    outputEvidence =
                        YDolbyVisionOutputEvidence(
                            stream = stream,
                            enhancementLayerComposed = true,
                        ),
                ),
            )
        assertTrue(measured.canClaimFelComposition)
    }

    @Test
    fun `P10 matches AV1 Dolby decoder and never an HEVC Dolby decoder`() {
        val decision =
            YDolbyVisionRouter.decide(
                video = video(YVideoCodec.Av1, profile = 10),
                evidence = evidence(profile = 10, compatibilityId = 1),
                capabilities =
                    capabilities(
                        decoders =
                            listOf(
                                decoder(
                                    name = "hevc.dv",
                                    codec = YVideoCodec.H265,
                                    hdr = setOf(YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(8),
                                ),
                                decoder(
                                    name = "av1.dv",
                                    codec = YVideoCodec.Av1,
                                    hdr = setOf(YHdrType.DolbyVision),
                                    dolbyProfiles = setOf(10),
                                ),
                            ),
                        display = setOf(YHdrType.DolbyVision),
                    ),
            )

        val native = assertIs<YDolbyVisionRouteDecision.Native>(decision)
        assertEquals("av1.dv", native.decoderName)
        assertEquals(YVideoCodec.Av1, native.codec)
    }

    private fun video(
        codec: YVideoCodec,
        profile: Int,
    ): YVideoRequirement =
        YVideoRequirement(
            codec = codec,
            width = 3840,
            height = 2160,
            frameRate = 23.976f,
            bitDepth = 10,
            hdrType = YHdrType.DolbyVision,
            dolbyVisionProfile = profile,
        )

    private fun evidence(
        profile: Int,
        compatibilityId: Int = 0,
        el: Boolean = false,
    ): YDolbyVisionStreamEvidence =
        YDolbyVisionStreamEvidence(
            config =
                YDolbyVisionConfig(
                    versionMajor = 1,
                    versionMinor = 0,
                    profile = profile,
                    level = 6,
                    rpuPresent = true,
                    enhancementLayerPresent = el,
                    baseLayerPresent = true,
                    baseLayerCompatibilityId = compatibilityId,
                    metadataCompression = 0,
                ),
            observedNals =
                YDolbyVisionNalEvidence(
                    rpuCount = 1,
                    enhancementLayerCount = if (el) 1 else 0,
                ),
        )

    private fun capabilities(
        decoders: List<YVideoDecoderCapability>,
        display: Set<YHdrType>,
    ): YDeviceCapabilities =
        YDeviceCapabilities(
            videoDecoders = decoders,
            displayHdrTypes = display + YHdrType.Sdr,
        )

    private fun decoder(
        name: String = "decoder",
        codec: YVideoCodec,
        hdr: Set<YHdrType>,
        dolbyProfiles: Set<Int> = emptySet(),
    ): YVideoDecoderCapability =
        YVideoDecoderCapability(
            name = name,
            codec = codec,
            hdrTypes = hdr + YHdrType.Sdr,
            dolbyVisionProfiles = dolbyProfiles,
            maxWidth = 7680,
            maxHeight = 4320,
            maxFrameRate = 120.0,
            maxBitDepth = 10,
        )
}
