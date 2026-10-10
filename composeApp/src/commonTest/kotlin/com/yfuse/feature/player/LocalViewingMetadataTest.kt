package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalViewingMetadataTest {
    @Test
    fun seriesIdentityAndCoordinatesSurviveWithoutPersistingPlaybackOrImageTokens() {
        val item =
            PlayerMediaItem(
                id = "episode-3",
                url = "https://server/video?api_key=secret",
                transcodeUrl = "",
                title = "第三集",
                serverId = "server",
                seriesId = "series",
                seriesName = "测试剧集",
                seriesKey = "tmdb:tv:100",
                seasonNumber = 2,
                episodeNumber = 3,
                mediaType = "Episode",
                posterUrl = "https://server/photo/:/transcode?url=%2Flibrary%2Fmetadata%2F100%2Fthumb%3Ftoken%3Dsecret&X-Plex-Token=secret",
            )
        val session = item.localViewingSession("viewing", 100L)
        assertEquals("tmdb:tv:100", session.seriesKey)
        assertEquals("测试剧集", session.displayTitle)
        assertEquals(2, session.seasonNumber)
        assertEquals(3, session.episodeNumber)
        assertEquals("series", session.posterItemId)
        assertEquals("plex:/library/metadata/100/thumb", session.posterTag)
        assertTrue(session.isEpisode)
        assertFalse(session.toString().contains("secret"))
    }

    @Test
    fun serverSpecificSeriesRemainDistinctAndMovieArtworkKeepsOnlyItsTag() {
        val item =
            PlayerMediaItem(
                id = "1",
                url = "",
                transcodeUrl = "",
                title = "片名",
                serverId = "first",
                mediaType = "Movie",
                posterUrl = "https://server/Items/1/Images/Primary?tag=art&api_key=secret",
            )
        val movie = item.localViewingSession("movie", 1L)
        assertFalse(movie.isEpisode)
        assertEquals("art", movie.posterTag)
        val episode = item.copy(seriesId = "show", mediaType = "Episode")
        assertEquals("first:show", episode.localViewingSession("a", 1L).seriesKey)
        assertEquals("second:show", episode.copy(serverId = "second").localViewingSession("b", 1L).seriesKey)
    }
}
