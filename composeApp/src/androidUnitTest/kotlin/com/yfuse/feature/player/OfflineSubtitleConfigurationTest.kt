package com.yfuse.feature.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineSubtitleConfigurationTest {
    @Test
    fun local_sidecar_becomes_default_subrip_track() {
        val subtitle =
            offlineSubtitleConfiguration(
                PlayerMediaItem(
                    id = "episode",
                    url = "file:///offline/episode.media",
                    transcodeUrl = "file:///offline/episode.media",
                    title = "Episode",
                    externalSubtitleUri = "file:///offline/episode.srt",
                    externalSubtitleLanguage = "chi",
                ),
            )!!

        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitle.mimeType)
        assertEquals("chi", subtitle.language)
        assertTrue(subtitle.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
    }

    @Test
    fun ordinary_stream_has_no_external_subtitle() {
        assertNull(
            offlineSubtitleConfiguration(
                PlayerMediaItem("movie", "https://media/movie", "https://media/movie", "Movie"),
            ),
        )
    }

    @Test
    fun sidecar_forces_the_engine_that_can_mount_it() {
        val plain = PlayerMediaItem("movie", "file:///movie", "file:///movie", "Movie")
        val withSubtitle = plain.copy(externalSubtitleUri = "file:///movie.srt")

        assertEquals(PlayerEngine.Mpv, offlineSubtitlePlaybackEngine(PlayerEngine.Mpv, listOf(plain)))
        assertEquals(PlayerEngine.Exo, offlineSubtitlePlaybackEngine(PlayerEngine.Mpv, listOf(withSubtitle)))
    }
}
