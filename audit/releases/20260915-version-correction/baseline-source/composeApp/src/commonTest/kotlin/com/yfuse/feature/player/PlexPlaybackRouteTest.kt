package com.yfuse.feature.player

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.SubtitleTrackInfo
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
                    subtitleTracks =
                        listOf(
                            SubtitleTrackInfo(
                                codec = "srt",
                                language = "中文",
                                external = true,
                                default = true,
                                uri =
                                    "http://plex:32400/library/streams/3?X-Plex-Token=secret",
                            ),
                        ),
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
        assertEquals(1, route.externalSubtitles.size)
        assertEquals("srt", route.externalSubtitles.single().codec)
        assertTrue(route.externalSubtitles.single().default)

        val rebound = route.withFreshPlaySession()
        assertTrue("session=${rebound.playSessionId}" in rebound.transcodeUrl)
        assertTrue("PlaySessionId=${rebound.playSessionId}" in rebound.transcodeUrl)
    }

    @Test
    fun plex_bif_storyboard_replaces_frame_index_with_millisecond_timestamp() {
        val storyboard =
            TrickplayStoryboard(
                urlPattern = "http://plex/library/parts/12/indexes/sd/{index}",
                width = 320,
                height = 180,
                tileColumns = 1,
                tileRows = 1,
                intervalMs = 10_000L,
                thumbnailCount = 10,
                urlIndexMultiplier = 10_000L,
            )

        assertEquals(
            "http://plex/library/parts/12/indexes/sd/20000",
            storyboard.frameAt(25_000L).url,
        )
    }
}
