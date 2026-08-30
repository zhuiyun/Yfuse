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
    fun supported_sidecar_formats_use_their_media3_mime_type() {
        val cases =
            mapOf(
                "file:///offline/episode.vtt" to MimeTypes.TEXT_VTT,
                "file:///offline/episode.ass" to MimeTypes.TEXT_SSA,
                "file:///offline/episode.ssa?token=secret" to MimeTypes.TEXT_SSA,
                "https://media.example.test/subtitle?format=ttml" to MimeTypes.APPLICATION_TTML,
                "file:///offline/episode.dfxp" to MimeTypes.APPLICATION_TTML,
            )

        cases.forEach { (uri, expectedMimeType) ->
            val subtitle =
                offlineSubtitleConfiguration(
                    PlayerMediaItem(
                        id = uri,
                        url = "file:///offline/episode.media",
                        transcodeUrl = "file:///offline/episode.media",
                        title = "Episode",
                        externalSubtitleUri = uri,
                    ),
                )!!
            assertEquals(expectedMimeType, subtitle.mimeType, uri)
        }
    }

    @Test
    fun unknown_sidecar_format_keeps_the_previous_subrip_fallback() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            externalSubtitleMimeType("content://offline/subtitle/42"),
        )
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
    fun plex_sidecars_are_all_attached_with_codec_and_selection_metadata() {
        val configurations =
            externalSubtitleConfigurations(
                PlayerMediaItem(
                    id = "movie",
                    url = "https://plex/movie.mkv",
                    transcodeUrl = "https://plex/movie.m3u8",
                    title = "Movie",
                    externalSubtitles =
                        listOf(
                            PlayerExternalSubtitle(
                                uri = "https://plex/library/streams/3?X-Plex-Token=secret",
                                language = "zho",
                                codec = "srt",
                                default = true,
                            ),
                            PlayerExternalSubtitle(
                                uri = "https://plex/library/streams/4?X-Plex-Token=secret",
                                language = "eng",
                                codec = "ass",
                            ),
                        ),
                ),
            )

        assertEquals(2, configurations.size)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, configurations[0].mimeType)
        assertTrue(configurations[0].selectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
        assertEquals(MimeTypes.TEXT_SSA, configurations[1].mimeType)
        assertEquals("eng", configurations[1].language)
    }

    @Test
    fun sidecar_does_not_override_the_selected_engine() {
        val plain = PlayerMediaItem("movie", "file:///movie", "file:///movie", "Movie")
        val withSubtitle = plain.copy(externalSubtitleUri = "file:///movie.srt")

        assertEquals(PlayerEngine.Mpv, offlineSubtitlePlaybackEngine(PlayerEngine.Mpv, listOf(plain)))
        assertEquals(PlayerEngine.Mpv, offlineSubtitlePlaybackEngine(PlayerEngine.Mpv, listOf(withSubtitle)))
    }
}
