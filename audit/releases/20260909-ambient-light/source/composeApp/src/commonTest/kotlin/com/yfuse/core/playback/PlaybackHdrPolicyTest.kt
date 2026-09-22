package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackHdrPolicyTest {
    @Test
    fun dolby_only_source_uses_exo_hardware_when_complete_pipeline_exists() {
        val route =
            playbackHdrRoute(
                source = dolby(needsDolbyDecoder = true),
                capabilities = capabilities(dolby = true),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Software,
            )

        assertEquals(PlayerEngine.Exo, route.engine)
        assertEquals(DecoderMode.Hardware, route.decoderMode)
        assertFalse(route.requiresServerTranscode)
    }

    @Test
    fun dolby_only_source_uses_local_mpv_without_dolby_display() {
        val route =
            playbackHdrRoute(
                source = dolby(needsDolbyDecoder = true),
                capabilities = capabilities(dolby = false),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
            )

        assertFalse(route.requiresServerTranscode)
        assertEquals(PlayerEngine.Mpv, route.engine)
        assertEquals(DecoderMode.Software, route.decoderMode)
        assertTrue(route.reason.orEmpty().contains("客户端"))
    }

    @Test
    fun unsupported_hdr_routes_to_mpv_tone_mapping_and_hardware_decode() {
        val route =
            playbackHdrRoute(
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = "HDR10",
                    ),
                capabilities = capabilities(dolby = false),
                preferredEngine = PlayerEngine.Mdk,
                preferredDecoderMode = DecoderMode.Software,
            )

        assertEquals(PlayerEngine.Mpv, route.engine)
        assertEquals(DecoderMode.Hardware, route.decoderMode)
        assertFalse(route.requiresServerTranscode)
    }

    @Test
    fun sdr_preserves_the_users_engine_and_decoder() {
        val route =
            playbackHdrRoute(
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = "SDR",
                    ),
                capabilities = capabilities(dolby = false),
                preferredEngine = PlayerEngine.Mdk,
                preferredDecoderMode = DecoderMode.Software,
            )

        assertEquals(PlayerEngine.Mdk, route.engine)
        assertEquals(DecoderMode.Software, route.decoderMode)
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
                preferredEngine = PlayerEngine.Exo,
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
