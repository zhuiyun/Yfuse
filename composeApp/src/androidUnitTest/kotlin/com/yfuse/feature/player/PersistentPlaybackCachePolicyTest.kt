package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistentPlaybackCachePolicyTest {
    @Test
    fun directPlayAndDirectStreamRemoteFilesCanUsePersistentCache() {
        val direct = mediaItem("https://media.example/Videos/42/stream.mkv?api_key=secret")
        val directStream = direct.copy(playMethod = PlaybackMethod.DirectStream)

        assertEquals(direct.url, direct.persistentPlaybackCacheUrl())
        assertEquals(directStream.url, directStream.persistentPlaybackCacheUrl())
    }

    @Test
    fun transcodesAdaptiveStreamsLocalFilesAndDiscSourcesBypassPersistentCache() {
        val direct = mediaItem("https://media.example/Videos/42/stream.mkv")
        val discVersion =
            PlayerMediaVersion(
                id = "disc",
                label = "Blu-ray",
                detail = "UHD Blu-ray",
                url = direct.url,
                transcodeUrl = "https://media.example/transcode/master.m3u8",
                fallbackTranscodeUrl = "https://media.example/transcode/fallback.mp4",
                container = "ISO",
                discSource = true,
            )

        assertNull(direct.copy(playMethod = PlaybackMethod.Transcode).persistentPlaybackCacheUrl())
        assertNull(direct.persistentPlaybackCacheUrl(usingServerTranscode = true))
        assertNull(mediaItem("https://media.example/live/master.m3u8").persistentPlaybackCacheUrl())
        assertNull(mediaItem("https://media.example/live/manifest.mpd").persistentPlaybackCacheUrl())
        assertNull(mediaItem("file:///storage/emulated/0/video.mkv").persistentPlaybackCacheUrl())
        assertNull(
            direct
                .copy(
                    versions = listOf(discVersion),
                    versionId = discVersion.id,
                ).persistentPlaybackCacheUrl(),
        )
    }

    private fun mediaItem(url: String): PlayerMediaItem =
        PlayerMediaItem(
            id = "item",
            url = url,
            transcodeUrl = "https://media.example/transcode/master.m3u8",
            title = "Episode",
        )
}
