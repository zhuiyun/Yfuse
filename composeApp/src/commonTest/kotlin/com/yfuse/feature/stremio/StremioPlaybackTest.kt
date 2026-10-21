package com.yfuse.feature.stremio

import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.StremioStream
import com.yfuse.core.stremio.StremioSubtitle
import com.yfuse.core.stremio.StremioVideo
import com.yfuse.feature.player.PlaybackReportingTarget
import com.yfuse.feature.player.isExternalPlayback
import com.yfuse.feature.player.playbackReportingTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioPlaybackTest {
    @Test
    fun signed_stream_plays_unchanged_without_server_reporting_or_transcode_fallback() {
        val url = "https://cdn.example/movie.m3u8?sig=A%2Bb&token=secret"
        val item =
            stremioPlaybackItem(
                StremioMeta("tt1", "movie", "Bunny"),
                StremioVideo("tt1"),
                StremioStream(url = url),
                emptyList(),
            )
        assertEquals(url, item.url)
        assertNull(item.serverId)
        assertTrue(item.isExternalPlayback)
        assertEquals(PlaybackReportingTarget.Disabled, playbackReportingTarget(item))
        assertEquals("", item.transcodeUrl)
        assertFalse(item.serverTranscodeSupported)
        assertFalse(item.id.contains("secret"))
    }

    @Test
    fun episode_identity_and_subtitle_deduplication_survive_reopening() {
        val meta = StremioMeta("tt1", "series", "Show")
        val video = StremioVideo("tt1:2:3", "Third", 2, 3)
        val subtitle = StremioSubtitle(url = "https://cdn.example/en.srt", lang = "eng")
        val stream = StremioStream(url = "https://cdn.example/one", subtitles = listOf(subtitle))
        val first = stremioPlaybackItem(meta, video, stream, listOf(subtitle, StremioSubtitle(url = "file:///private")))
        val second = stremioPlaybackItem(meta, video, stream.copy(url = "https://cdn.example/two"), emptyList())
        assertEquals(first.id, second.id)
        assertEquals(2, first.seasonNumber)
        assertEquals(3, first.episodeNumber)
        assertEquals(1, first.externalSubtitles.size)
        assertEquals("eng", first.externalSubtitles.single().language)
    }

    @Test
    fun unsupported_sources_cannot_be_handed_to_the_player() {
        assertFailsWith<IllegalArgumentException> {
            stremioPlaybackItem(
                StremioMeta("tt1", "movie", "Bunny"),
                StremioVideo("tt1"),
                StremioStream(infoHash = "hash"),
                emptyList(),
            )
        }
    }
}
