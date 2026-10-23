package com.yfuse.core.widget

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetNavigationTest {
    @Test
    fun explicit_tmdb_identity_keeps_card_metadata_for_the_standard_detail_loading_state() {
        val media =
            WidgetMedia(
                id = "source-42",
                title = "A film",
                tmdbId = "42",
                mediaType = "movie",
                description = "An overview",
                poster = "https://image.tmdb.org/t/p/w780/poster.jpg",
                backdrop = "https://images.example/backdrop.jpg",
                year = "2026-10-23",
                rating = "8.5",
                link = "https://source.example/title/42",
                videoUrl = "https://source.example/video.mp4",
            )
        val item = requireNotNull(media.tmdbDetailItemOrNull())
        assertEquals(42, item.id)
        assertEquals(media.title, item.title)
        assertEquals(media.description, item.overview)
        assertEquals(media.poster, item.posterPath)
        assertEquals(media.backdrop, item.backdropPath)
        assertEquals("2026", item.year)
        assertEquals("movie", item.mediaType)
        assertEquals(8.5, item.rating)
    }

    @Test
    fun declared_tmdb_items_can_use_the_numeric_id_and_normalize_series_type() {
        listOf("tv", "series", " Series ").forEach { type ->
            val item =
                requireNotNull(
                    WidgetMedia(id = " 042 ", title = "Series", type = " TMDB ", mediaType = type)
                        .tmdbDetailItemOrNull(),
                )
            assertEquals(42, item.id)
            assertEquals("tv", item.mediaType)
        }
    }

    @Test
    fun ordinary_source_ids_and_playback_links_never_become_guessed_tmdb_ids() {
        val source =
            WidgetMedia(
                id = "42",
                title = "Source title",
                mediaType = "movie",
                link = "https://source.example/title/42",
                videoUrl = "https://source.example/video.mp4",
            )
        assertNull(source.tmdbDetailItemOrNull())
        assertNull(source.copy(type = "url").tmdbDetailItemOrNull())
        assertNull(source.copy(id = source.link, type = "tmdb").tmdbDetailItemOrNull())
    }

    @Test
    fun invalid_explicit_ids_are_not_replaced_with_an_unrelated_numeric_source_id() {
        listOf("0", "-1", "+1", "1.0", "1e3", "2147483648", "movie:42", "４２").forEach { id ->
            assertNull(
                WidgetMedia(id = "42", title = "Invalid", type = "tmdb", tmdbId = id)
                    .tmdbDetailItemOrNull(),
                id,
            )
        }
        assertNull(WidgetMedia(id = "", title = "No identity", type = "tmdb").tmdbDetailItemOrNull())
    }

    @Test
    fun categories_and_non_movie_entities_do_not_open_movie_details() {
        val media = WidgetMedia(id = "42", title = "Item", tmdbId = "42")
        assertNull(media.copy(categoryParams = buildJsonObject { put("genre", "drama") }).tmdbDetailItemOrNull())
        listOf("", "person", "episode", "collection", "unknown").forEach { type ->
            assertNull(media.copy(mediaType = type).tmdbDetailItemOrNull(), type)
        }
    }

    @Test
    fun invalid_optional_metadata_does_not_invent_a_rating_or_year() {
        val media = WidgetMedia(id = "42", title = "Movie", tmdbId = "42", year = "unknown")
        val item = requireNotNull(media.tmdbDetailItemOrNull())
        assertNull(item.year)
        assertNull(item.overview)
        assertNull(item.posterPath)
        assertNull(item.backdropPath)
        assertNull(item.rating)
        listOf("NaN", "Infinity", "-1", "11", "bad").forEach { value ->
            assertNull(requireNotNull(media.copy(rating = value).tmdbDetailItemOrNull()).rating)
        }
    }

    @Test
    fun metadata_only_cards_open_standard_details_without_needing_a_source_loader() {
        val movie = WidgetMedia(id = "source-42", title = "Movie", tmdbId = "42", year = "2026")
        assertEquals(movie.tmdbDetailItemOrNull(), movie.standardTmdbDetailItemOrNull())
        val series = movie.copy(id = "42", tmdbId = "", type = "tmdb", mediaType = "series", link = "  ")
        assertEquals(series.tmdbDetailItemOrNull(), series.standardTmdbDetailItemOrNull())
        assertNull(movie.copy(tmdbId = "").standardTmdbDetailItemOrNull())
    }

    @Test
    fun official_tmdb_information_links_open_the_matching_standard_details() {
        val movie = WidgetMedia(id = "movie.42", title = "Movie", type = "tmdb", tmdbId = "42")
        listOf(
            "https://www.themoviedb.org/movie/42",
            "https://themoviedb.org/movie/42/",
            "http://www.themoviedb.org/movie/42",
            "http://themoviedb.org:80/movie/42",
            "https://www.themoviedb.org:443/movie/42",
            " HTTPS://WWW.THEMOVIEDB.ORG/movie/42 ",
        ).forEach { link ->
            assertEquals(movie.tmdbDetailItemOrNull(), movie.copy(link = link).standardTmdbDetailItemOrNull(), link)
        }
        val series = movie.copy(mediaType = "series", link = "https://www.themoviedb.org/tv/42")
        assertEquals("tv", requireNotNull(series.standardTmdbDetailItemOrNull()).mediaType)
    }

    @Test
    fun films_with_playback_or_episode_routes_still_open_the_default_detail_page() {
        val movie =
            WidgetMedia(
                id = "movie.42",
                title = "Movie",
                type = "tmdb",
                tmdbId = "42",
                link = "https://www.themoviedb.org/movie/42",
                headers = mapOf("Referer" to "https://source.example/"),
            )
        val episode = WidgetMedia(id = "episode-1", title = "Episode 1", videoUrl = "https://source.example/1.m3u8")
        listOf("", movie.link).forEach { link ->
            val expected = movie.tmdbDetailItemOrNull()
            assertEquals(
                expected,
                movie.copy(link = link, videoUrl = "https://source.example/movie.m3u8").standardTmdbDetailItemOrNull(),
            )
            assertEquals(expected, movie.copy(link = link, children = listOf(episode)).standardTmdbDetailItemOrNull())
            assertEquals(
                expected,
                movie.copy(link = link, children = listOf(episode.copy(videoUrl = ""))).standardTmdbDetailItemOrNull(),
            )
        }
    }

    @Test
    fun source_links_cannot_override_the_declared_tmdb_detail_destination() {
        val movie =
            WidgetMedia(
                id = "42",
                title = "Movie",
                type = "tmdb",
                tmdbId = "42",
                headers = mapOf("Referer" to "https://source.example/"),
            )
        listOf(
            "https://source.example/detail/42",
            "https://www.themoviedb.org/tv/42",
            "https://www.themoviedb.org/movie/43",
            "https://www.themoviedb.org/movie/42/watch",
            "https://www.themoviedb.org/movie/42?source=custom",
            "https://www.themoviedb.org/movie/42#play",
            "https://www.themoviedb.org/movie/%34%32",
            "https://www.themoviedb.org/movie/042",
            "https://www.themoviedb.org/movie/2147483648",
            "https://www.themoviedb.org:8443/movie/42",
            "http://www.themoviedb.org:443/movie/42",
            "https://user:password@www.themoviedb.org/movie/42",
            "https://@www.themoviedb.org/movie/42",
            "https://www.themoviedb.org.evil.example/movie/42",
            "https://www.themoviedb.org@evil.example/movie/42",
            "https://evil.example@www.themoviedb.org/movie/42",
            "https://api.themoviedb.org/movie/42",
            "https://www.themoviedb.org/../movie/42",
            "ftp://www.themoviedb.org/movie/42",
            "//www.themoviedb.org/movie/42",
        ).forEach { link ->
            assertEquals(movie.tmdbDetailItemOrNull(), movie.copy(link = link).standardTmdbDetailItemOrNull(), link)
            assertNull(movie.copy(type = "url", tmdbId = "", link = link).standardTmdbDetailItemOrNull(), link)
        }
    }
}
