package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPlannerTest {
    @Test
    fun only_ycore_is_an_available_engine() {
        assertEquals(listOf("YCore"), PlayerEngine.entries.map { it.name })
        assertEquals(listOf("Auto", "LockYCore"), PlaybackEngineSelection.entries.map { it.name })
    }

    @Test
    fun automatic_modes_use_ycore() {
        PlaybackOptimizationMode.entries.forEach { optimization ->
            val plan =
                planPlayback(
                    probe("mp4"),
                    capabilities(),
                    PlayerEngine.YCore,
                    DecoderMode.Auto,
                    optimizationMode = optimization,
                    excludedEngines = setOf(PlayerEngine.YCore),
                    engineCosts = mapOf(PlayerEngine.YCore to 100),
                    availableEngines = PlayerEngine.entries.toSet(),
                )
            assertEquals(listOf(PlayerEngine.YCore), plan.engineOrder)
            assertEquals(PlayerEngine.YCore, plan.primaryEngine)
            assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        }
    }

    @Test
    fun no_available_selected_engine_fails() {
        assertFailsWith<IllegalStateException> {
            planPlayback(
                probe("mp4"),
                capabilities(),
                PlayerEngine.YCore,
                DecoderMode.Auto,
                availableEngines = emptySet(),
            )
        }
    }

    @Test
    fun protected_content_does_not_claim_secure_output_or_server_bypass() {
        PlaybackEngineSelection.entries.forEach { selection ->
            val plan =
                planPlayback(
                    probe("mp4", transcode = true).copy(drmProtected = true),
                    capabilities(),
                    PlayerEngine.YCore,
                    DecoderMode.Auto,
                    engineSelection = selection,
                    availableEngines = PlayerEngine.entries.toSet(),
                    videoSupport = PlaybackVideoSupport.unsupported("secure decoder absent"),
                )
            assertFalse(plan.requiresServerTranscode)
            assertEquals(selection.lockedEngine ?: PlayerEngine.YCore, plan.primaryEngine)
            assertTrue(plan.reason.orEmpty().contains("DRM"))
        }
    }

    @Test
    fun native_demux_disc_styled_subtitles_and_large_remote_mov_keep_ycore() {
        val ordinary = probe("mkv")
        val probes =
            listOf(
                probe("iso", disc = true),
                probe("avi"),
                ordinary.copy(styledSubtitles = true),
                probe("mov").copy(sourceSizeBytes = 80L * 1024 * 1024 * 1024),
                ordinary.copy(audioCodec = PlaybackAudioCodec.TrueHd),
            )
        probes.forEach { media ->
            val plan = planPlayback(media, capabilities(), PlayerEngine.YCore, DecoderMode.Auto)
            assertEquals(listOf(PlayerEngine.YCore), plan.engineOrder)
            assertFalse(plan.requiresServerTranscode)
            assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        }
    }

    @Test
    fun unresolved_disc_with_server_support_keeps_local_native_disc_pipeline() {
        val plan =
            planPlayback(
                probe("iso", disc = true, transcode = true),
                capabilities(),
                PlayerEngine.YCore,
                DecoderMode.Auto,
            )
        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlayerEngine.YCore, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
    }

    @Test
    fun hardware_rejection_uses_local_software_decoding_even_when_server_transcode_exists() {
        listOf(false, true).forEach { server ->
            val plan =
                planPlayback(
                    probe("mp4", transcode = server),
                    capabilities(),
                    PlayerEngine.YCore,
                    DecoderMode.Hardware,
                    videoSupport = PlaybackVideoSupport.unsupported("exact format rejected"),
                )
            assertFalse(plan.requiresServerTranscode)
            assertEquals(DecoderMode.Software, plan.decoderMode)
            assertEquals(listOf(PlayerEngine.YCore), plan.engineOrder)
        }
    }

    @Test
    fun prores_keeps_original_software_attempt_even_when_server_exists() {
        val media =
            probe("mov", transcode = true).let {
                it.copy(source = it.source.copy(videoCodec = PlaybackVideoCodec.ProRes))
            }
        val plan =
            planPlayback(
                media,
                capabilities(),
                PlayerEngine.YCore,
                DecoderMode.Auto,
                videoSupport = PlaybackVideoSupport.unsupported("ProRes is not a platform decoder"),
            )
        assertEquals(DecoderMode.Software, plan.decoderMode)
        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
    }

    @Test
    fun display_dolby_support_is_not_native_runtime_output_proof() {
        val media =
            probe("mkv").let {
                it.copy(
                    source =
                        it.source.copy(
                            dolbyVision = true,
                            needsDolbyDecoder = true,
                            dolbyVisionProfile = 5,
                        ),
                )
            }
        val plan = planPlayback(media, capabilities(dolby = true), PlayerEngine.YCore, DecoderMode.Auto)
        assertEquals(PlaybackDolbyVisionPath.Unverified, plan.dolbyVisionPath)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
    }

    @Test
    fun unsupported_dolby_does_not_request_automatic_server_conversion() {
        val media =
            probe("mkv", transcode = true).let {
                it.copy(source = it.source.copy(dolbyVision = true, needsDolbyDecoder = true, dolbyVisionProfile = 5))
            }
        val plan =
            planPlayback(
                media,
                capabilities(),
                PlayerEngine.YCore,
                DecoderMode.Auto,
                videoSupport = PlaybackVideoSupport.unsupported("no native Dolby decoder"),
            )
        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlaybackDolbyVisionPath.Unverified, plan.dolbyVisionPath)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
    }

    @Test
    fun sdr_display_keeps_local_hdr_pipeline_without_server_transcode() {
        listOf(false, true).forEach { server ->
            val media =
                probe("mkv", transcode = server).let {
                    it.copy(
                        source =
                            it.source.copy(
                                dynamicRange = "HDR10",
                                videoCodec = PlaybackVideoCodec.Hevc,
                            ),
                    )
                }
            val plan = planPlayback(media, capabilities(), PlayerEngine.YCore, DecoderMode.Auto)
            assertFalse(plan.requiresServerTranscode)
            assertFalse(plan.renderPath == PlaybackRenderPath.GpuToneMapped)
        }
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
