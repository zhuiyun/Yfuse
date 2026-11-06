package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core2.android.toCore2MediaItems
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineSubtitleConfigurationTest {
    @Test
    fun local_sidecar_becomes_default_subrip_track() {
        val subtitle =
            nativeSubtitleConfiguration(
                PlayerMediaItem(
                    id = "episode",
                    url = "file:///offline/episode.media",
                    transcodeUrl = "file:///offline/episode.media",
                    title = "Episode",
                    externalSubtitleUri = "file:///offline/episode.srt",
                    externalSubtitleLanguage = "chi",
                ),
            )!!

        assertEquals(YSubtitleFormat.Srt, subtitle.format)
        assertEquals("chi", subtitle.language)
        assertTrue(subtitle.default)
    }

    @Test
    fun supported_sidecar_formats_reach_the_native_subtitle_loader() {
        val cases =
            mapOf(
                "file:///offline/episode.vtt" to YSubtitleFormat.WebVtt,
                "file:///offline/episode.ass" to YSubtitleFormat.Ass,
                "file:///offline/episode.ssa?token=secret" to YSubtitleFormat.Ssa,
                "https://media.example.test/subtitle?format=ttml" to YSubtitleFormat.Ttml,
                "file:///offline/episode.dfxp" to YSubtitleFormat.Ttml,
            )

        cases.forEach { (uri, expectedMimeType) ->
            val subtitle =
                nativeSubtitleConfiguration(
                    PlayerMediaItem(
                        id = uri,
                        url = "file:///offline/episode.media",
                        transcodeUrl = "file:///offline/episode.media",
                        title = "Episode",
                        externalSubtitleUri = uri,
                    ),
                )!!
            assertEquals(expectedMimeType, subtitle.format, uri)
        }
    }

    @Test
    fun unknown_sidecar_format_remains_unconfirmed_for_content_detection() {
        val item =
            PlayerMediaItem(
                "episode",
                "file:///movie",
                "",
                "Episode",
                externalSubtitleUri = "content://offline/subtitle/42",
            )
        assertNull(assertNotNull(nativeSubtitleConfiguration(item)).format)
    }

    @Test
    fun ordinary_stream_has_no_external_subtitle() {
        assertNull(
            nativeSubtitleConfiguration(
                PlayerMediaItem("movie", "https://media/movie", "https://media/movie", "Movie"),
            ),
        )
    }

    @Test
    fun plex_sidecars_are_all_attached_with_codec_and_selection_metadata() {
        val configurations =
            nativeSubtitleConfigurations(
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
        assertEquals(YSubtitleFormat.Srt, configurations[0].format)
        assertTrue(configurations[0].default)
        assertEquals(YSubtitleFormat.Ass, configurations[1].format)
        assertEquals("eng", configurations[1].language)
    }

    @Test
    fun sidecar_does_not_override_the_selected_engine() {
        val plain = PlayerMediaItem("movie", "file:///movie", "file:///movie", "Movie")
        val withSubtitle = plain.copy(externalSubtitleUri = "file:///movie.srt")

        for (engine in PlayerEngine.entries) {
            assertEquals(engine, offlineSubtitlePlaybackEngine(engine, listOf(plain)))
            assertEquals(engine, offlineSubtitlePlaybackEngine(engine, listOf(withSubtitle)))
        }
    }

    private fun nativeSubtitleConfigurations(item: PlayerMediaItem): List<YExternalSubtitleSource> =
        listOf(item).toCore2MediaItems("").single().allExternalSubtitles

    private fun nativeSubtitleConfiguration(item: PlayerMediaItem): YExternalSubtitleSource? =
        nativeSubtitleConfigurations(item).firstOrNull()
}
