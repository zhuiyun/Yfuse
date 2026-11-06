package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackHdrPolicyTest {
    @Test
    fun dolby_display_does_not_prove_native_output_or_software_tone_mapping() {
        listOf(false, true).forEach { display ->
            val route = playbackHdrRoute(dolby(true), capabilities(display), PlayerEngine.YCore, DecoderMode.Auto)
            assertEquals(PlayerEngine.YCore, route.engine)
            assertEquals(PlaybackDolbyVisionPath.Unverified, route.dolbyVisionPath)
            assertEquals(DecoderMode.Hardware, route.decoderMode)
            assertFalse(route.requiresServerTranscode)
            assertFalse(route.stripDolbyVisionToBaseLayer)
        }
    }

    @Test
    fun sen_hdr_remains_an_unverified_explicit_request() {
        val route = playbackHdrRoute(dolby(true), capabilities(true), PlayerEngine.SenPlayer, DecoderMode.Auto)
        assertEquals(PlayerEngine.SenPlayer, route.engine)
        assertEquals(PlaybackDolbyVisionPath.Unverified, route.dolbyVisionPath)
        assertEquals(DecoderMode.Auto, route.decoderMode)
    }

    @Test
    fun sdr_preserves_decoder_intent_without_hdr_claims() {
        val route =
            playbackHdrRoute(
                PlaybackSourceRequirements(false, false, "SDR"),
                capabilities(false),
                PlayerEngine.YCore,
                DecoderMode.Software,
            )
        assertEquals(PlayerEngine.YCore, route.engine)
        assertEquals(DecoderMode.Software, route.decoderMode)
        assertEquals(PlaybackDolbyVisionPath.None, route.dolbyVisionPath)
        assertFalse(route.requiresServerTranscode)
    }

    @Test
    fun exact_decoder_rejection_forces_server_transcode_before_rendering() {
        val route =
            playbackHdrRoute(
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = "HDR10",
                        videoCodec = PlaybackVideoCodec.Hevc,
                        width = 3_840,
                        height = 2_160,
                        frameRate = 59.94,
                        bitrateBitsPerSecond = 80_000_000,
                        bitDepth = 10,
                    ),
                capabilities = capabilities(dolby = false),
                preferredEngine = PlayerEngine.YCore,
                preferredDecoderMode = DecoderMode.Auto,
                videoSupport =
                    PlaybackVideoSupport.unsupported(
                        "decoder: 不支持 3840x2160@59.94fps",
                    ),
            )

        assertTrue(route.requiresServerTranscode)
        assertEquals("decoder: 不支持 3840x2160@59.94fps", route.reason)
        assertEquals(DecoderMode.Hardware, route.decoderMode)
    }

    @Test
    fun compatible_dolby_base_layer_is_checked_as_hevc_hdr10() {
        val source =
            PlaybackSourceRequirements(
                dolbyVision = true,
                needsDolbyDecoder = false,
                dynamicRange = "Dolby Vision P8",
                videoCodec = PlaybackVideoCodec.Hevc,
                bitDepth = 10,
            )

        assertEquals(PlaybackVideoCodec.Hevc, source.videoRequirements.codec)
        assertEquals(PlaybackHdrFormat.Hdr10, source.videoRequirements.hdrFormat)
    }

    private fun dolby(needsDolbyDecoder: Boolean) =
        PlaybackSourceRequirements(
            dolbyVision = true,
            needsDolbyDecoder = needsDolbyDecoder,
            dynamicRange = "Dolby Vision P5",
        )

    private fun capabilities(dolby: Boolean): PlaybackDeviceCapabilities =
        PlaybackDeviceCapabilities(
            hdrFormats =
                if (dolby) {
                    setOf(PlaybackHdrFormat.DolbyVision, PlaybackHdrFormat.Hdr10)
                } else {
                    emptySet()
                },
            videoDecoders =
                if (dolby) {
                    setOf(PlaybackVideoCodec.H264, PlaybackVideoCodec.DolbyVision)
                } else {
                    setOf(PlaybackVideoCodec.H264)
                },
            hdrDecoders =
                if (dolby) {
                    mapOf(
                        PlaybackVideoCodec.DolbyVision to
                            setOf(PlaybackHdrFormat.DolbyVision),
                        PlaybackVideoCodec.Hevc to setOf(PlaybackHdrFormat.Hdr10),
                    )
                } else {
                    emptyMap()
                },
            audioDecoders = setOf(PlaybackAudioCodec.Aac),
            directAudioFormats = emptySet(),
            dolbyVisionCodecProfiles = emptySet(),
            dolbyVisionBaseCodecs =
                setOf(PlaybackVideoCodec.Hevc).takeIf { dolby }.orEmpty(),
            audioRoutes = setOf(PlaybackAudioRoute.BuiltIn),
            maxAudioChannels = 2,
        )
}
