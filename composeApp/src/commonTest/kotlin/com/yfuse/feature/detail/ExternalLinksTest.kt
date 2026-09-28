package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalLinksTest {
    @Test
    fun a_series_opens_the_tv_pages_and_a_film_the_movie_pages() {
        assertEquals(
            listOf(
                "TMDB" to "https://www.themoviedb.org/tv/1399",
                "IMDb" to "https://www.imdb.com/title/tt0944947/",
                "TheTVDB" to "https://thetvdb.com/dereferrer/series/121361",
            ),
            externalLinks(mapOf("Tmdb" to "1399", "Imdb" to "tt0944947", "Tvdb" to "121361"), type = "Series"),
        )
        assertEquals(
            listOf(
                "TMDB" to "https://www.themoviedb.org/movie/603",
                "TheTVDB" to "https://thetvdb.com/dereferrer/movie/169",
            ),
            externalLinks(mapOf("tmdb" to "603", "tvdb" to "169"), type = "Movie"),
        )
    }

    @Test
    fun an_episode_reaches_tmdb_through_its_series_or_not_at_all() {
        val episode = mapOf("Tmdb" to "63056", "Tvdb" to "3254641")

        assertEquals(
            listOf(
                "TMDB" to "https://www.themoviedb.org/tv/1399/season/1/episode/1",
                "TheTVDB" to "https://thetvdb.com/dereferrer/episode/3254641",
            ),
            externalLinks(episode, type = "Episode", seriesTmdbId = "1399", seasonNumber = 1, episodeNumber = 1),
        )
        // The episode's own TMDB id numbers the episode: /tv/63056 would be some other show.
        assertEquals(
            listOf("TheTVDB" to "https://thetvdb.com/dereferrer/episode/3254641"),
            externalLinks(episode, type = "Episode"),
        )
    }

    @Test
    fun a_collection_opens_its_tmdb_collection_and_a_season_no_page_of_the_wrong_kind() {
        assertEquals(
            listOf("TMDB" to "https://www.themoviedb.org/collection/2344"),
            externalLinks(mapOf("Tmdb" to "2344", "Tvdb" to "77"), type = "BoxSet"),
        )
        // A season's TMDB id is not its show's, and TheTVDB's season pages are not dereferred here.
        assertEquals(
            listOf("IMDb" to "https://www.imdb.com/title/tt1234567/"),
            externalLinks(mapOf("Tmdb" to "3624", "Tvdb" to "364731", "Imdb" to "tt1234567"), type = "Season"),
        )
    }
}
