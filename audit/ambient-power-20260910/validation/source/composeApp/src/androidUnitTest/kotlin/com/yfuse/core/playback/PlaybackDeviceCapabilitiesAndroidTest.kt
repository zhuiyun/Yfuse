package com.yfuse.core.playback

import android.media.MediaCodecInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDeviceCapabilitiesAndroidTest {
    @Test
    fun ordinary_hevc_main10_does_not_claim_hdr10_but_can_carry_hlg() {
        val formats =
            decoderHdrFormats(
                PlaybackVideoCodec.Hevc,
                listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
            )

        assertEquals(setOf(PlaybackHdrFormat.Hlg), formats)
    }

    @Test
    fun explicit_hevc_hdr10_plus_profile_includes_hdr10_compatibility() {
        val formats =
            decoderHdrFormats(
                PlaybackVideoCodec.Hevc,
                listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus),
            )

        assertTrue(PlaybackHdrFormat.Hdr10Plus in formats)
        assertTrue(PlaybackHdrFormat.Hdr10 in formats)
    }

    @Test
    fun dolby_decoder_must_advertise_at_least_one_profile() {
        assertFalse(
            PlaybackHdrFormat.DolbyVision in
                decoderHdrFormats(PlaybackVideoCodec.DolbyVision, emptyList()),
        )
        assertTrue(
            PlaybackHdrFormat.DolbyVision in
                decoderHdrFormats(PlaybackVideoCodec.DolbyVision, listOf(1)),
        )
    }

    @Test
    fun ten_bit_hevc_requires_a_main10_decoder_profile() {
        assertFalse(
            decoderSupportsBitDepth(
                codec = PlaybackVideoCodec.Hevc,
                profiles = listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
                bitDepth = 10,
            ),
        )
        assertTrue(
            decoderSupportsBitDepth(
                codec = PlaybackVideoCodec.Hevc,
                profiles = listOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                bitDepth = 10,
            ),
        )
    }

    @Test
    fun refresh_rate_only_display_callbacks_do_not_invalidate_hdr_capabilities() {
        val hdr = setOf(PlaybackHdrFormat.Hdr10, PlaybackHdrFormat.Hlg)

        assertFalse(displayCapabilitiesChanged(hdr, hdr.toSet()))
        assertTrue(displayCapabilitiesChanged(hdr, hdr + PlaybackHdrFormat.DolbyVision))
    }
}
