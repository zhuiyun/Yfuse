package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDolbyVisionRoutingTest {
    @Test
    fun planner_exposes_gpu_dolby_render_path_for_profile_seven() {
        val plan =
            planPlayback(
                probe = probe(profileSeven()),
                capabilities = capabilities(dolby = true, hdr10 = true),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                dolbyVisionRuntime = verifiedRuntime(fullFelGpuCapable = true),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.GpuDolbyVision, plan.renderPath)
        assertEquals(PlaybackDolbyVisionPath.MpvGpuNext, plan.dolbyVisionPath)
        assertFalse(plan.requiresServerTranscode)
    }

    @Test
    fun planner_exposes_native_platform_path_for_profile_eight_one() {
        val plan =
            planPlayback(
                probe = probe(profileEightOne()),
                capabilities = capabilities(dolby = true, hdr10 = true),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Software,
                dolbyVisionRuntime = verifiedRuntime(fullFelGpuCapable = true),
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.PlatformDirect, plan.renderPath)
        assertEquals(PlaybackDolbyVisionPath.MediaCodecNative, plan.dolbyVisionPath)
    }

    @Test
    fun profile_seven_enhancement_uses_verified_gpu_next_when_gpu_budget_is_sufficient() {
        val route =
            playbackDolbyVisionRoute(
                source = profileSeven(),
                capabilities = capabilities(dolby = true, hdr10 = true),
                runtime = verifiedRuntime(fullFelGpuCapable = true),
            )

        assertEquals(PlaybackDolbyVisionPath.MpvGpuNext, route.path)
        assertEquals(PlayerEngine.Mpv, route.engine)
        assertEquals(DecoderMode.Hardware, route.decoderMode)
        assertFalse(route.stripDolbyVisionToBaseLayer)
    }

    @Test
    fun profile_seven_enhancement_falls_back_to_real_hdr10_base_when_gpu_is_constrained() {
        val route =
            playbackDolbyVisionRoute(
                source = profileSeven(),
                capabilities = capabilities(dolby = true, hdr10 = true),
                runtime = verifiedRuntime(fullFelGpuCapable = false),
            )

        assertEquals(PlaybackDolbyVisionPath.Hdr10BaseLayer, route.path)
        assertEquals(PlayerEngine.Mpv, route.engine)
        assertTrue(route.stripDolbyVisionToBaseLayer)
        assertTrue(route.reason.contains("GPU"))
    }

    @Test
    fun profile_eight_one_uses_native_mediacodec_on_a_dolby_display() {
        val route =
            playbackDolbyVisionRoute(
                source = profileEightOne(),
                capabilities = capabilities(dolby = true, hdr10 = true),
                runtime = verifiedRuntime(fullFelGpuCapable = true),
            )

        assertEquals(PlaybackDolbyVisionPath.MediaCodecNative, route.path)
        assertEquals(PlayerEngine.Exo, route.engine)
        assertEquals(DecoderMode.Hardware, route.decoderMode)
    }

    @Test
    fun profile_eight_one_uses_hdr10_base_without_a_dolby_display() {
        val route =
            playbackDolbyVisionRoute(
                source = profileEightOne(),
                capabilities = capabilities(dolby = false, hdr10 = true),
                runtime = verifiedRuntime(fullFelGpuCapable = true),
            )

        assertEquals(PlaybackDolbyVisionPath.Hdr10BaseLayer, route.path)
        assertTrue(route.stripDolbyVisionToBaseLayer)
    }

    @Test
    fun compatible_base_is_tone_mapped_on_an_sdr_only_display() {
        val route =
            playbackDolbyVisionRoute(
                source = profileEightOne(),
                capabilities = capabilities(dolby = false, hdr10 = false),
                runtime = verifiedRuntime(fullFelGpuCapable = true),
            )

        assertEquals(PlaybackDolbyVisionPath.SdrToneMap, route.path)
        assertEquals(PlayerEngine.Mpv, route.engine)
        assertTrue(route.stripDolbyVisionToBaseLayer)
        assertTrue(route.reason.contains("SDR"))
    }

    @Test
    fun raw_disc_never_routes_profile_eight_one_to_exo() {
        val route =
            playbackDolbyVisionRoute(
                source = profileEightOne(),
                capabilities = capabilities(dolby = true, hdr10 = true),
                runtime = verifiedRuntime(fullFelGpuCapable = true),
                requiresNativeDemuxer = true,
            )

        assertEquals(PlayerEngine.Mpv, route.engine)
        assertEquals(PlaybackDolbyVisionPath.Hdr10BaseLayer, route.path)
    }

    private fun profileSeven() =
        PlaybackSourceRequirements(
            dolbyVision = true,
            needsDolbyDecoder = false,
            dynamicRange = "Dolby Vision P7",
            dolbyVisionProfile = 7,
            dolbyRpuPresent = true,
            dolbyEnhancementLayerPresent = true,
            dolbyBaseLayerPresent = true,
            dolbyBaseLayerCompatibilityId = 0,
            videoCodec = PlaybackVideoCodec.Hevc,
            bitDepth = 10,
        )

    private fun profileEightOne() =
        PlaybackSourceRequirements(
            dolbyVision = true,
            needsDolbyDecoder = false,
            dynamicRange = "Dolby Vision P8.1",
            dolbyVisionProfile = 8,
            dolbyRpuPresent = true,
            dolbyEnhancementLayerPresent = false,
            dolbyBaseLayerPresent = true,
            dolbyBaseLayerCompatibilityId = 1,
            videoCodec = PlaybackVideoCodec.Hevc,
            bitDepth = 10,
        )

    private fun verifiedRuntime(fullFelGpuCapable: Boolean) =
        PlaybackDolbyVisionRuntimeCapabilities(
            verifiedMpvRpu = true,
            verifiedMpvFel = true,
            fullFelGpuCapable = fullFelGpuCapable,
        )

    private fun probe(source: PlaybackSourceRequirements) =
        PlaybackMediaProbe(
            container = "MKV",
            discSource = false,
            source = source,
            hasServerTranscode = true,
        )

    private fun capabilities(
        dolby: Boolean,
        hdr10: Boolean,
    ) = PlaybackDeviceCapabilities(
        hdrFormats =
            buildSet {
                if (dolby) add(PlaybackHdrFormat.DolbyVision)
                if (hdr10) add(PlaybackHdrFormat.Hdr10)
            },
        videoDecoders =
            buildSet {
                add(PlaybackVideoCodec.Hevc)
                if (dolby) add(PlaybackVideoCodec.DolbyVision)
            },
        hdrDecoders =
            buildMap {
                put(
                    PlaybackVideoCodec.Hevc,
                    setOf(PlaybackHdrFormat.Hdr10).takeIf { hdr10 }.orEmpty(),
                )
                if (dolby) {
                    put(
                        PlaybackVideoCodec.DolbyVision,
                        setOf(PlaybackHdrFormat.DolbyVision),
                    )
                }
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
