package com.yfuse.core2.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YCapabilityTest {
    @Test
    fun secureRequirementRejectsAdvertisedNonSecureDecoder() {
        val decoder =
            YVideoDecoderCapability(
                name = "decoder",
                codec = YVideoCodec.H265,
                securePlayback = false,
            )

        assertFalse(
            decoder.supports(
                YVideoRequirement(codec = YVideoCodec.H265, secureDecodeRequired = true),
            ),
        )
    }

    @Test
    fun surfaceRequirementRejectsByteBufferOnlyDecoder() {
        val decoder =
            YVideoDecoderCapability(
                name = "decoder",
                codec = YVideoCodec.H264,
                surfaceOutput = false,
            )

        assertFalse(decoder.supports(YVideoRequirement(codec = YVideoCodec.H264)))
        assertTrue(
            decoder.supports(
                YVideoRequirement(codec = YVideoCodec.H264, surfaceOutputRequired = false),
            ),
        )
    }

    @Test
    fun portraitVideoUsesTheSameDecoderSizeEnvelopeAsLandscape() {
        val decoder =
            YVideoDecoderCapability(
                name = "decoder",
                codec = YVideoCodec.H264,
                maxWidth = 4_096,
                maxHeight = 2_160,
            )

        assertTrue(
            decoder.supports(
                YVideoRequirement(codec = YVideoCodec.H264, width = 1_440, height = 3_040),
            ),
        )
        assertFalse(
            decoder.supports(
                YVideoRequirement(codec = YVideoCodec.H264, width = 2_200, height = 4_100),
            ),
        )
    }

    @Test
    fun immersiveExtensionsCanUseBaseCarrierWithoutBecomingVerifiedAtmos() {
        val capabilities =
            YDeviceCapabilities(
                videoDecoders = emptyList(),
                audioPassthrough = setOf(YAudioCodec.Eac3, YAudioCodec.TrueHd),
            )

        assertEquals(
            YAudioOutputPath.Passthrough,
            capabilities.audioOutputPath(YAudioRequirement(YAudioCodec.Eac3Joc)),
        )
        assertEquals(
            YAudioOutputPath.Passthrough,
            capabilities.audioOutputPath(YAudioRequirement(YAudioCodec.TrueHdAtmos)),
        )
        assertFalse(capabilities.hasExactDolbyAtmosPassthrough(YAudioCodec.Eac3Joc))
        assertFalse(capabilities.hasExactDolbyAtmosPassthrough(YAudioCodec.TrueHdAtmos))
    }

    @Test
    fun exactJocCapabilityIsPositiveAtmosTransportEvidence() {
        val capabilities =
            YDeviceCapabilities(
                videoDecoders = emptyList(),
                audioPassthrough = setOf(YAudioCodec.Eac3Joc),
            )

        assertTrue(capabilities.hasExactDolbyAtmosPassthrough(YAudioCodec.Eac3Joc))
    }
}
