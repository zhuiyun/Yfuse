package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UhdBluRayPlaybackPlannerTest {
    @Test
    fun server_resolved_hdr10_main_feature_stays_direct_and_preserves_truehd() {
        val probe = resolvedBluRayProbe()
        val plan =
            planPlayback(
                probe = probe,
                capabilities = hdr10Capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                videoSupport = PlaybackVideoSupport.supported("HEVC Main10 3840x2160@23.976"),
            )

        assertFalse(probe.requiresNativeDemuxer)
        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.PlatformDirect, plan.renderPath)
        assertTrue(plan.reason.orEmpty().contains("保留原始音视频流"))
    }

    @Test
    fun pgs_on_a_resolved_bluray_main_feature_uses_native_renderer_without_transcoding() {
        val probe = resolvedBluRayProbe().copy(styledSubtitles = true)
        val plan =
            planPlayback(
                probe = probe,
                capabilities = hdr10Capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                videoSupport = PlaybackVideoSupport.supported("HEVC Main10 3840x2160@23.976"),
            )

        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
    }

    @Test
    fun dolby_vision_only_resolved_main_feature_uses_verified_platform_dolby_path() {
        val probe =
            resolvedBluRayProbe().copy(
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = true,
                        needsDolbyDecoder = true,
                        dynamicRange = "Dolby Vision P7",
                        videoCodec = PlaybackVideoCodec.DolbyVision,
                        width = 3_840,
                        height = 2_160,
                        frameRate = 23.976,
                        bitDepth = 10,
                    ),
            )
        val plan =
            planPlayback(
                probe = probe,
                capabilities = dolbyCapabilities(),
                preferredEngine = PlayerEngine.Mpv,
                preferredDecoderMode = DecoderMode.Auto,
                videoSupport = PlaybackVideoSupport.supported("Dolby Vision decoder"),
            )

        assertFalse(plan.requiresServerTranscode)
        assertEquals(PlayerEngine.Exo, plan.primaryEngine)
        assertEquals(DecoderMode.Hardware, plan.decoderMode)
        assertEquals(PlaybackRenderPath.PlatformDirect, plan.renderPath)
    }

    @Test
    fun unresolved_remote_iso_still_uses_server_main_feature_fallback() {
        val probe =
            resolvedBluRayProbe().copy(
                container = "ISO",
                discKind = PlaybackDiscKind.Iso,
                discMainFeatureResolved = false,
            )
        val plan =
            planPlayback(
                probe = probe,
                capabilities = hdr10Capabilities(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Hardware,
                videoSupport = PlaybackVideoSupport.supported("HEVC Main10"),
            )

        assertTrue(plan.requiresServerTranscode)
        assertEquals(PlaybackRenderPath.ServerTranscode, plan.renderPath)
    }

    private fun resolvedBluRayProbe() =
        PlaybackMediaProbe(
            container = "BLURAY",
            discSource = true,
            source =
                PlaybackSourceRequirements(
                    dolbyVision = false,
                    needsDolbyDecoder = false,
                    dynamicRange = "HDR10",
                    videoCodec = PlaybackVideoCodec.Hevc,
                    width = 3_840,
                    height = 2_160,
                    frameRate = 23.976,
                    bitDepth = 10,
                ),
            hasServerTranscode = true,
            discKind = PlaybackDiscKind.BluRay,
            discMainFeatureResolved = true,
            audioCodec = PlaybackAudioCodec.TrueHd,
            audioChannelCount = 8,
        )

    private fun hdr10Capabilities() =
        PlaybackDeviceCapabilities(
            hdrFormats = setOf(PlaybackHdrFormat.Hdr10),
            videoDecoders = setOf(PlaybackVideoCodec.H264, PlaybackVideoCodec.Hevc),
            hdrDecoders = mapOf(PlaybackVideoCodec.Hevc to setOf(PlaybackHdrFormat.Hdr10)),
            audioDecoders = setOf(PlaybackAudioCodec.Aac, PlaybackAudioCodec.TrueHd),
            directAudioFormats = setOf(PlaybackAudioCodec.TrueHd),
            dolbyVisionCodecProfiles = emptySet(),
            dolbyVisionBaseCodecs = emptySet(),
            audioRoutes = setOf(PlaybackAudioRoute.Hdmi),
            maxAudioChannels = 8,
        )

    private fun dolbyCapabilities() =
        PlaybackDeviceCapabilities(
            hdrFormats = setOf(PlaybackHdrFormat.Hdr10, PlaybackHdrFormat.DolbyVision),
            videoDecoders =
                setOf(
                    PlaybackVideoCodec.H264,
                    PlaybackVideoCodec.Hevc,
                    PlaybackVideoCodec.DolbyVision,
                ),
            hdrDecoders =
                mapOf(
                    PlaybackVideoCodec.Hevc to setOf(PlaybackHdrFormat.Hdr10),
                    PlaybackVideoCodec.DolbyVision to setOf(PlaybackHdrFormat.DolbyVision),
                ),
            audioDecoders = setOf(PlaybackAudioCodec.Aac, PlaybackAudioCodec.TrueHd),
            directAudioFormats = setOf(PlaybackAudioCodec.TrueHd),
            dolbyVisionCodecProfiles = setOf(1),
            dolbyVisionBaseCodecs = setOf(PlaybackVideoCodec.Hevc),
            audioRoutes = setOf(PlaybackAudioRoute.Hdmi),
            maxAudioChannels = 8,
        )
}
