package com.yfuse.core2.android

import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackSourceRequirements
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.feature.player.PlayerMediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPlaybackProbeFactsTest {
    private val media =
        PlayerMediaItem(
            "movie",
            "https://host/movie?token=private",
            "",
            "Movie",
            serverId = "server",
            playSessionId = "session",
        )
    private val source =
        YMediaItem(
            media.id,
            media.url,
            headers = mapOf("User-Agent" to "client"),
            providerKey = media.serverId,
            playbackSessionId = media.playSessionId,
        )
    private val result =
        YCore2ProbeResult.Success(
            YPlaybackRequest(
                YContainer.Matroska,
                YVideoRequirement(YVideoCodec.H264, 1920, 1080),
                platformDemuxSupported = true,
            ),
            "video/avc",
            "audio/mp4a-latm",
            1000L,
        )

    @Test
    fun facts_are_never_applied_to_another_source_or_session() {
        val facts = AndroidPlaybackProbeFacts(source, result)
        assertTrue(facts.matches(media, "client"))
        assertFalse(facts.matches(media.copy(url = "https://host/refreshed"), "client"))
        assertFalse(facts.matches(media.copy(serverId = "other"), "client"))
        assertFalse(facts.matches(media.copy(playSessionId = "new-session"), "client"))
        assertFalse(facts.matches(media, "different-client"))
        assertFalse(facts.toString().contains("token"))
    }

    @Test
    fun core_truth_clears_incorrect_server_dolby_claims() {
        val baseline =
            PlaybackMediaProbe(
                "mkv",
                false,
                PlaybackSourceRequirements(
                    true,
                    true,
                    "Dolby Vision",
                    dolbyVisionProfile = 7,
                    dolbyRpuPresent = true,
                    dolbyBaseLayerPresent = true,
                ),
                false,
            )
        val actual = AndroidPlaybackProbeFacts(source, result).presentation(baseline).probe
        assertEquals(1920, actual.source.width)
        assertEquals(1000L, actual.durationMs)
        assertFalse(actual.source.dolbyVision)
        assertNull(actual.source.dolbyVisionProfile)
        assertNull(actual.source.dolbyRpuPresent)
        assertNull(actual.source.dolbyBaseLayerPresent)
    }
}
