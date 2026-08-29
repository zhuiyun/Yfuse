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
    fun balanced_mode_uses_device_performance_history_for_equivalent_engines() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                engineCosts = mapOf(PlayerEngine.Exo to 6),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(listOf(PlayerEngine.Mpv, PlayerEngine.Mdk, PlayerEngine.Exo), plan.engineOrder)
    }

    @Test
    fun drm_remains_on_the_platform_path_even_when_history_prefers_native() {
        val plan =
            planPlayback(
                probe = probe(container = "mp4").copy(drmProtected = true),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Auto,
                engineCosts = mapOf(PlayerEngine.Exo to 10),
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(listOf(PlayerEngine.Exo), plan.engineOrder)
    }

    @Test
    fun locked_backend_disables_automatic_reranking_and_fallback() {
        val plan =
            planPlayback(
                probe = probe(container = "avi"),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                engineSelection = PlaybackEngineSelection.LockMdk,
                excludedEngines = setOf(PlayerEngine.Mdk),
                engineCosts = mapOf(PlayerEngine.Mdk to 100),
            )

        assertEquals(PlayerEngine.Mdk, plan.primaryEngine)
        assertEquals(listOf(PlayerEngine.Mdk), plan.engineOrder)
        assertTrue(plan.reason.orEmpty().contains("已锁定"))
    }

    @Test
    fun protected_content_safely_overrides_a_native_backend_lock() {
        val plan =
            planPlayback(
                probe = probe(container = "mp4").copy(drmProtected = true),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Software,
                engineSelection = PlaybackEngineSelection.LockMpv,
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(listOf(PlayerEngine.Exo), plan.engineOrder)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
        assertTrue(plan.reason.orEmpty().contains("安全输出"))
    }

    @Test
    fun platform_dolby_output_safely_overrides_a_native_backend_lock() {
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
                engineSelection = PlaybackEngineSelection.LockMpv,
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(listOf(PlayerEngine.Exo), plan.engineOrder)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
        assertEquals(PlaybackDolbyVisionPath.MediaCodecNative, plan.dolbyVisionPath)
        assertTrue(plan.reason.orEmpty().contains("Dolby"))
    }

    @Test
    fun unsupported_hardware_uses_local_software_when_server_cannot_transcode() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv", transcode = false),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                videoSupport = PlaybackVideoSupport.unsupported("分辨率超出硬解范围"),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(DecoderMode.Software, plan.decoderMode)
        assertFalse(plan.requiresServerTranscode)
    }

    @Test
    fun unsupported_hardware_prefers_an_available_server_transcode() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv", transcode = true),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                videoSupport = PlaybackVideoSupport.unsupported("码率超出硬解范围"),
            )

        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
        assertTrue(plan.requiresServerTranscode)
    }

    @Test
    fun dolby_vision_stays_on_local_mpv_even_when_server_transcode_exists() {
        val dolbyProbe =
            PlaybackMediaProbe(
                container = "mkv",
                discSource = false,
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = true,
                        needsDolbyDecoder = true,
                        dynamicRange = "Dolby Vision P5",
                        videoCodec = PlaybackVideoCodec.Hevc,
                        bitDepth = 10,
                    ),
                hasServerTranscode = true,
            )

        val plan =
            planPlayback(
                probe = dolbyProbe,
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                optimizationMode = PlaybackOptimizationMode.PowerSaver,
                videoSupport = PlaybackVideoSupport.unsupported("平台没有 Dolby 解码器"),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(DecoderMode.Software, plan.decoderMode)
        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlaybackRenderPath.GpuToneMapped, plan.renderPath)
    }

    @Test
    fun prores_12bit_prefers_original_ffmpeg_decode_even_when_server_can_transcode() {
        val proResProbe =
            PlaybackMediaProbe(
                container = "MOV",
                discSource = false,
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = "SDR",
                        videoCodec = PlaybackVideoCodec.ProRes,
                        width = 1_920,
                        height = 1_080,
                        frameRate = 24.0,
                        bitDepth = 12,
                    ),
                hasServerTranscode = true,
                audioCodec = PlaybackAudioCodec.Pcm,
                audioChannelCount = 2,
            )
        val plan =
            planPlayback(
                probe = proResProbe,
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                engineCosts = mapOf(PlayerEngine.Mpv to 100),
                videoSupport = PlaybackVideoSupport.unsupported("Android 平台没有可验证的 ProRes 硬解"),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(DecoderMode.Software, plan.decoderMode)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        assertFalse(plan.requiresServerTranscode)
        assertTrue(plan.reason.orEmpty().contains("ProRes"))
        assertTrue(plan.reason.orEmpty().contains("FFmpeg"))
    }

    @Test
    fun unsupported_platform_audio_keeps_native_demux_ahead_of_performance_ranking() {
        val plan =
            planPlayback(
                probe = probe(container = "mkv").copy(audioCodec = PlaybackAudioCodec.TrueHd),
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                engineCosts = mapOf(PlayerEngine.Mpv to 10),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertTrue(plan.reason.orEmpty().contains("TrueHd"))
    }

    @Test
    fun passthrough_only_audio_is_not_directly_playable_when_passthrough_is_disabled() {
        val trueHdRoute =
            capabilities().copy(
                directAudioFormats = setOf(PlaybackAudioCodec.TrueHd),
            )
        val source = probe(container = "mkv").copy(audioCodec = PlaybackAudioCodec.TrueHd)

        val pcmPlan =
            planPlayback(
                probe = source,
                capabilities = trueHdRoute,
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                allowAudioPassthrough = false,
            )
        val passthroughPlan =
            planPlayback(
                probe = source,
                capabilities = trueHdRoute,
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                allowAudioPassthrough = true,
            )

        assertEquals(PlayerEngine.Mpv, pcmPlan.primaryEngine)
        assertEquals(PlayerEngine.Exo, passthroughPlan.primaryEngine)
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

    @Test
    fun huge_remote_mov_uses_native_demux_and_keeps_every_backend_in_the_fallback_chain() {
        val source =
            probe(container = "mov").copy(
                sourceSizeBytes = 195_738_044_172L,
                localSource = false,
            )

        val plan =
            planPlayback(
                probe = source,
                capabilities = capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
            )

        assertTrue(source.isHugeRemoteMov)
        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(listOf(PlayerEngine.Mpv, PlayerEngine.Exo, PlayerEngine.Mdk), plan.engineOrder)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        assertFalse(plan.requiresServerTranscode)
    }

    private fun probe(
        container: String,
        disc: Boolean = false,
        transcode: Boolean = false,
    ) = PlaybackMediaProbe(
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
