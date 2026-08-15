package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPlannerTest {
    @Test
    fun ordinary_supported_video_preserves_the_efficient_platform_path() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.PlatformDirect, plan.renderPath)
        assertFalse(plan.requiresServerTranscode)
    }

    @Test
    fun known_exotic_container_uses_native_demux_before_platform_failure() {
        val plan =
            planPlayback(
                probe = probe(container = "avi"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        assertTrue(plan.reason.orEmpty().contains("AVI"))
    }

    @Test
    fun disc_source_with_server_fallback_uses_server_parse_and_platform_decode() {
        val plan =
            planPlayback(
                probe = probe(container = "iso", disc = true, transcode = true),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Software,
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
        assertEquals(PlaybackRenderPath.ServerTranscode, plan.renderPath)
        assertTrue(plan.requiresServerTranscode)
    }

    @Test
    fun power_saver_overrides_a_native_preference_for_supported_content() {
        val plan =
            planPlayback(
                probe = probe(container = "mp4"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Mdk,
                preferredDecoderMode = DecoderMode.Auto,
                optimizationMode = PlaybackOptimizationMode.PowerSaver,
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
        assertEquals(listOf(PlayerEngine.Exo, PlayerEngine.Mdk, PlayerEngine.Mpv), plan.engineOrder)
    }

    @Test
    fun power_saver_uses_server_tone_mapping_instead_of_running_the_gpu_path() {
        val hdrProbe =
            PlaybackMediaProbe(
                container = "mkv",
                discSource = false,
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = "HDR10",
                        videoCodec = PlaybackVideoCodec.Hevc,
                        bitDepth = 10,
                    ),
                hasServerTranscode = true,
            )
        val decoderOnlyCapabilities =
            capabilities().copy(
                hdrDecoders =
                    mapOf(
                        PlaybackVideoCodec.Hevc to setOf(PlaybackHdrFormat.Hdr10),
                    ),
            )

        val plan =
            planPlayback(
                probe = hdrProbe,
                capabilities = decoderOnlyCapabilities,
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                optimizationMode = PlaybackOptimizationMode.PowerSaver,
            )

        assertTrue(plan.requiresServerTranscode)
        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.ServerTranscode, plan.renderPath)
    }

    @Test
    fun compatibility_mode_prefers_mpv_but_keeps_platform_as_a_fallback() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                optimizationMode = PlaybackOptimizationMode.Compatibility,
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertTrue(PlayerEngine.Exo in plan.engineOrder)
    }

    @Test
    fun repeated_device_failure_removes_the_bad_engine_from_the_plan() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                excludedEngines = setOf(PlayerEngine.Exo),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertFalse(PlayerEngine.Exo in plan.engineOrder)
    }

    @Test
    fun dolby_only_source_never_routes_original_frames_through_a_native_engine() {
        val dolbySource =
            PlaybackSourceRequirements(
                dolbyVision = true,
                needsDolbyDecoder = true,
                dynamicRange = "Dolby Vision P5",
                videoCodec = PlaybackVideoCodec.DolbyVision,
            )
        val plan =
            planPlayback(
                probe =
                    PlaybackMediaProbe(
                        container = "mp4",
                        discSource = false,
                        source = dolbySource,
                        hasServerTranscode = true,
                    ),
                capabilities = capabilities(dolby = true),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Software,
            )

        assertEquals(listOf(PlayerEngine.Exo), plan.engineOrder)
        assertEquals(PlaybackRenderPath.PlatformDirect, plan.renderPath)
    }

    private fun probe(
        container: String,
        disc: Boolean = false,
        transcode: Boolean = false,
    ) =
        PlaybackMediaProbe(
            container = container,
            discSource = disc,
            source =
                PlaybackSourceRequirements(
                    dolbyVision = false,
                    needsDolbyDecoder = false,
                    dynamicRange = "SDR",
                    videoCodec = PlaybackVideoCodec.H264,
                    width = 1_920,
                    height = 1_080,
                    frameRate = 23.976,
                    bitDepth = 8,
                ),
            hasServerTranscode = transcode,
        )

    private fun capabilities(dolby: Boolean = false): PlaybackDeviceCapabilities =
        PlaybackDeviceCapabilities(
            hdrFormats =
                if (dolby) {
                    setOf(PlaybackHdrFormat.DolbyVision, PlaybackHdrFormat.Hdr10)
                } else {
                    emptySet()
                },
            videoDecoders =
                buildSet {
                    add(PlaybackVideoCodec.H264)
                    add(PlaybackVideoCodec.Hevc)
                    if (dolby) add(PlaybackVideoCodec.DolbyVision)
                },
            hdrDecoders =
                if (dolby) {
                    mapOf(
                        PlaybackVideoCodec.DolbyVision to setOf(PlaybackHdrFormat.DolbyVision),
                        PlaybackVideoCodec.Hevc to setOf(PlaybackHdrFormat.Hdr10),
                    )
                } else {
                    emptyMap()
                },
            audioDecoders = setOf(PlaybackAudioCodec.Aac),
            directAudioFormats = emptySet(),
            dolbyVisionCodecProfiles = emptySet(),
            dolbyVisionBaseCodecs =
                if (dolby) setOf(PlaybackVideoCodec.Hevc) else emptySet(),
            audioRoutes = setOf(PlaybackAudioRoute.BuiltIn),
            maxAudioChannels = 2,
        )
}
