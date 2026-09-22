package com.yfuse.tv.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvLocalPlaybackPolicyTest {
    @Test
    fun removes_transcode_routes_from_items_versions_and_server_fallbacks() {
        val fallback = directItem(id = "fallback")
        val sanitized =
            directItem(id = "episode")
                .copy(serverFallbacks = listOf(fallback))
                .withoutServerTranscodeForTv()

        assertEquals("https://media/original/episode", sanitized.url)
        assertTrue(sanitized.transcodeUrl.isEmpty())
        assertTrue(sanitized.fallbackTranscodeUrl.isEmpty())
        assertFalse(sanitized.serverTranscodeSupported)
        assertTrue(sanitized.versions.single().transcodeUrl.isEmpty())
        assertTrue(sanitized.serverFallbacks.single().transcodeUrl.isEmpty())
    }

    @Test
    fun a_server_selected_transcode_recovers_an_available_direct_version() {
        val direct = directVersion("direct")
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "https://server/transcode/movie.m3u8",
                transcodeUrl = "https://server/transcode/movie.m3u8",
                fallbackTranscodeUrl = "https://server/transcode/movie.mp4",
                title = "Movie",
                versions = listOf(transcodeVersion("server"), direct),
                versionId = "server",
                playMethod = PlaybackMethod.Transcode,
                serverTranscodeSupported = true,
                forcedTranscodeReason = "device profile",
            ).withoutServerTranscodeForTv()

        assertEquals(direct.url, item.url)
        assertEquals("direct", item.versionId)
        assertEquals(PlaybackMethod.DirectPlay, item.playMethod)
        assertEquals(listOf("direct"), item.versions.map { it.id })
        assertEquals(null, item.forcedTranscodeReason)
    }

    @Test
    fun a_transcode_only_source_is_refused_instead_of_starting_server_decode() {
        val item =
            PlayerMediaItem(
                id = "unsupported",
                url = "https://server/transcode/unsupported.m3u8",
                transcodeUrl = "https://server/transcode/unsupported.m3u8",
                title = "Unsupported",
                versions = listOf(transcodeVersion("only")),
                versionId = "only",
                playMethod = PlaybackMethod.Transcode,
            ).withoutServerTranscodeForTv()

        assertTrue(item.url.isEmpty())
        assertTrue(item.versions.isEmpty())
        assertTrue(item.transcodeUrl.isEmpty())
        assertEquals(PlaybackMethod.Transcode, item.playMethod)
    }

    @Test
    fun direct_stream_is_kept_because_it_remuxes_without_server_decode() {
        val item =
            directItem(id = "remux")
                .copy(playMethod = PlaybackMethod.DirectStream)
                .withoutServerTranscodeForTv()

        assertEquals("https://media/original/remux", item.url)
        assertEquals(PlaybackMethod.DirectStream, item.playMethod)
        assertTrue(item.transcodeUrl.isEmpty())
    }
}

private fun directItem(id: String): PlayerMediaItem {
    val version = directVersion(id)
    return PlayerMediaItem(
        id = id,
        url = version.url,
        transcodeUrl = version.transcodeUrl,
        fallbackTranscodeUrl = version.fallbackTranscodeUrl,
        title = id,
        versions = listOf(version),
        versionId = id,
        serverTranscodeSupported = true,
    )
}

private fun directVersion(id: String): PlayerMediaVersion =
    PlayerMediaVersion(
        id = id,
        label = id,
        detail = "",
        url = "https://media/original/$id",
        transcodeUrl = "https://server/transcode/$id.m3u8",
        fallbackTranscodeUrl = "https://server/transcode/$id.mp4",
        playMethod = PlaybackMethod.DirectPlay,
        serverTranscodeSupported = true,
    )

private fun transcodeVersion(id: String): PlayerMediaVersion =
    PlayerMediaVersion(
        id = id,
        label = id,
        detail = "",
        url = "https://server/transcode/$id.m3u8",
        transcodeUrl = "https://server/transcode/$id.m3u8",
        fallbackTranscodeUrl = "https://server/transcode/$id.mp4",
        playMethod = PlaybackMethod.Transcode,
        serverTranscodeSupported = true,
    )
