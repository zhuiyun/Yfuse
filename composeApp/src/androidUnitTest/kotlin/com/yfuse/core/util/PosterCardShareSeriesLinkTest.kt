package com.yfuse.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosterCardShareSeriesLinkTest {
    @Test
    fun an_episode_is_never_linked_as_a_show_by_its_own_id() {
        // 63056 is an episode's own TMDB id; /tv/63056 is some unrelated series.
        assertNull(tmdbTitleUrl("63056", "Episode"))
        assertNull(tmdbTitleUrl("3624", "Season"))
    }

    @Test
    fun an_episode_links_under_its_series() {
        assertEquals(
            "https://www.themoviedb.org/tv/1399/season/1/episode/2",
            tmdbTitleUrl("63056", "Episode", seriesTmdbId = "1399", seasonNumber = 1, episodeNumber = 2),
        )
    }

    @Test
    fun missing_numbers_fall_back_to_the_season_and_then_the_series() {
        assertEquals(
            "https://www.themoviedb.org/tv/1399/season/1",
            tmdbTitleUrl("63056", "episode", seriesTmdbId = "1399", seasonNumber = 1),
        )
        assertEquals(
            "https://www.themoviedb.org/tv/1399",
            tmdbTitleUrl("63056", "Episode", seriesTmdbId = "1399", episodeNumber = 2),
        )
        assertEquals(
            "https://www.themoviedb.org/tv/1399/season/1",
            tmdbTitleUrl("63056", "Episode", seriesTmdbId = "1399", seasonNumber = 1, episodeNumber = 0),
        )
    }

    @Test
    fun a_season_links_to_its_season_page_specials_included() {
        assertEquals(
            "https://www.themoviedb.org/tv/1399/season/0",
            tmdbTitleUrl("3624", "Season", seriesTmdbId = "1399", seasonNumber = 0, episodeNumber = 5),
        )
    }

    @Test
    fun the_series_id_is_held_to_the_same_numeric_rule() {
        assertNull(tmdbTitleUrl("63056", "Episode", seriesTmdbId = "1399/../../evil", seasonNumber = 1))
        assertNull(tmdbTitleUrl("63056", "Episode", seriesTmdbId = "http://192.168.1.5:8096"))
    }

    @Test
    fun an_episode_card_without_its_series_carries_no_tmdb_line() {
        assertEquals(
            "《凛冬将至》",
            posterCardCaption(PosterShareCard(title = "凛冬将至", tmdbId = "63056", mediaType = "Episode")),
        )
        assertEquals(
            "《凛冬将至》\nTMDB：https://www.themoviedb.org/tv/1399/season/1/episode/1",
            posterCardCaption(
                PosterShareCard(
                    title = "凛冬将至",
                    tmdbId = "63056",
                    mediaType = "Episode",
                    seriesTmdbId = "1399",
                    seasonNumber = 1,
                    episodeNumber = 1,
                ),
            ),
        )
    }
}
