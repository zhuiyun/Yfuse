package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProResPlaybackPlanningTest {
    @Test
    fun prores_12bit_prefers_original_ffmpeg_even_when_server_can_transcode() {
        val probe =
            PlaybackMediaProbe(
                container = "MOV",
                discSource = false,
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = "SDR",
                        videoCodec = PlaybackVideoCodec.ProRes,
                        width = 1920,
                        height = 1080,
                        frameRate = 24.0,
                        bitDepth = 12,
                    ),
                hasServerTranscode = true,
                audioCodec = PlaybackAudioCodec.Pcm,
            )
        val plan =
            planPlayback(
                probe = probe,
                capabilities = PlaybackDeviceCapabilities.conservative(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
                videoSupport = PlaybackVideoSupport.unknown("Android 没有 ProRes MIME"),
                engineCosts = mapOf(PlayerEngine.Mpv to 100),
            )

        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(DecoderMode.Software, plan.decoderMode)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        assertFalse(plan.requiresServerTranscode)
        assertTrue(plan.reason.orEmpty().contains("ProRes"))
        assertTrue(plan.reason.orEmpty().contains("FFmpeg"))
    }
}
