package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDiscStrategy
import com.yfuse.core.playback.PlaybackRenderPath
import com.yfuse.core.playback.planDiscPlayback
import com.yfuse.core.playback.planPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeRemoteBluRayPlanningTest {
    @Test
    fun registered_raw_iso_uses_mpv_even_though_server_fallback_urls_are_retained() {
        val item = rawDiscItem(url = "yfusebd://17", method = PlaybackMethod.DirectPlay)
        val probe = item.playbackMediaProbe()

        assertFalse(probe.hasServerTranscode)
        assertEquals(PlaybackDiscStrategy.NativeRemoteFallback, planDiscPlayback(probe).strategy)

        val plan =
            planPlayback(
                probe = probe,
                capabilities = PlaybackDeviceCapabilities.conservative(),
                preferredEngine = PlayerEngine.Exo,
                preferredDecoderMode = DecoderMode.Auto,
            )
        assertEquals(PlayerEngine.Mpv, plan.primaryEngine)
        assertEquals(PlaybackRenderPath.NativeDirect, plan.renderPath)
        assertFalse(plan.requiresServerTranscode)
    }

    @Test
    fun unregistered_remote_iso_keeps_server_main_feature_fallback() {
        val item = rawDiscItem(url = "https://media.example/master.m3u8", method = PlaybackMethod.Transcode)
        val probe = item.playbackMediaProbe(usingServerTranscode = false)

        assertTrue(probe.hasServerTranscode)
        assertEquals(PlaybackDiscStrategy.ServerMainFeature, planDiscPlayback(probe).strategy)
    }

    private fun rawDiscItem(
        url: String,
        method: PlaybackMethod,
    ): PlayerMediaItem {
        val version =
            PlayerMediaVersion(
                id = "iso-source",
                label = "UHD Blu-ray ISO",
                detail = "ISO",
                url = url,
                transcodeUrl = "https://media.example/master.m3u8",
                fallbackTranscodeUrl = "https://media.example/stream.mp4",
                container = "ISO",
                discSource = true,
                playMethod = method,
            )
        return PlayerMediaItem(
            id = "movie",
            url = url,
            transcodeUrl = version.transcodeUrl,
            fallbackTranscodeUrl = version.fallbackTranscodeUrl,
            title = "Movie",
            serverId = "server",
            versions = listOf(version),
            versionId = version.id,
            playMethod = method,
        )
    }
}
