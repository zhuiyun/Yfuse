package com.yfuse.feature.player

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.VideoStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlexPlaybackRouteTest {
    @Test
    fun plex_original_part_wins_over_emby_generated_url_even_for_dolby_vision() {
        val route =
            listOf(
                MediaVersion(
                    id = "7",
                    name = "4K DV",
                    container = "mkv",
                    sizeBytes = null,
                    bitrateBps = null,
                    videoCodec = "hevc",
                    videoHeight = 2160,
                    videoRange = "Dolby Vision",
                    video = VideoStreamInfo(width = 3840, height = 2160, dolbyProfile = 8),
                    supportsDirectPlay = false,
                    supportsDirectStream = true,
                    supportsTranscoding = true,
                    directStreamUrl =
                        "http://plex:32400/library/parts/12/file.mkv?X-Plex-Token=secret",
                    addApiKeyToDirectStreamUrl = false,
                    transcodingUrl =
                        "http://plex:32400/video/:/transcode/universal/start.m3u8?X-Plex-Token=secret",
                ),
            ).toPlayerMediaVersions(
                baseUrl = "http://plex:32400",
                itemId = "100",
                token = "secret",
                negotiatedPlaySessionId = "session",
                localCleartextConfirmed = true,
            ).single()

        assertEquals(PlaybackMethod.DirectStream, route.playMethod)
        assertTrue("/library/parts/12/file.mkv" in route.url)
        assertTrue("X-Plex-Token=secret" in route.url)
        assertFalse("/Videos/100/stream" in route.url)

        val rebound = route.withFreshPlaySession()
        assertTrue("session=${rebound.playSessionId}" in rebound.transcodeUrl)
        assertTrue("PlaySessionId=${rebound.playSessionId}" in rebound.transcodeUrl)
    }
}
