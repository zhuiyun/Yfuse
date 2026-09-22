package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpvExternalSubtitleCommandTest {
    @Test
    fun selected_sidecar_is_mounted_and_selected_without_switching_engine() {
        val item =
            PlayerMediaItem(
                id = "episode",
                url = "file:///offline/episode.mkv",
                transcodeUrl = "file:///offline/episode.mkv",
                title = "Episode",
                externalSubtitleUri = "file:///offline/episode.ass",
                externalSubtitleLanguage = "zh-CN",
            )

        assertContentEquals(
            arrayOf(
                "sub-add",
                "file:///offline/episode.ass",
                "select",
                "外挂字幕",
                "zh-CN",
            ),
            externalSubtitleMpvCommand(item),
        )
    }

    @Test
    fun item_without_sidecar_does_not_issue_an_mpv_command() {
        assertNull(
            externalSubtitleMpvCommand(
                PlayerMediaItem(
                    id = "episode",
                    url = "file:///offline/episode.mkv",
                    transcodeUrl = "file:///offline/episode.mkv",
                    title = "Episode",
                ),
            ),
        )
    }

    @Test
    fun every_plex_sidecar_is_mounted_and_only_the_default_is_selected() {
        val commands =
            externalSubtitleMpvCommands(
                PlayerMediaItem(
                    id = "movie",
                    url = "https://plex/movie.mkv",
                    transcodeUrl = "https://plex/movie.m3u8",
                    title = "Movie",
                    externalSubtitles =
                        listOf(
                            PlayerExternalSubtitle("https://plex/sub/zh", "zho", "srt", default = true),
                            PlayerExternalSubtitle("https://plex/sub/en", "eng", "ass"),
                        ),
                ),
            )

        assertEquals(2, commands.size)
        assertContentEquals(
            arrayOf("sub-add", "https://plex/sub/zh", "select", "zho · SRT", "zho"),
            commands[0],
        )
        assertContentEquals(
            arrayOf("sub-add", "https://plex/sub/en", "auto", "eng · ASS", "eng"),
            commands[1],
        )
    }
}
