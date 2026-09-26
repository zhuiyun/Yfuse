package com.yfuse.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosterCardShareTest {
    @Test
    fun tmdb_links_follow_the_kind_and_refuse_anything_but_a_number() {
        assertEquals("https://www.themoviedb.org/movie/603", tmdbTitleUrl("603", "Movie"))
        assertEquals("https://www.themoviedb.org/tv/1399", tmdbTitleUrl(" 1399 ", "Series"))
        assertEquals("https://www.themoviedb.org/tv/1399", tmdbTitleUrl("1399", "tv"))
        assertEquals("https://www.themoviedb.org/movie/603", tmdbTitleUrl("603", null))
        assertNull(tmdbTitleUrl(null, "Movie"))
        assertNull(tmdbTitleUrl("", "Movie"))
        assertNull(tmdbTitleUrl("603/../../evil", "Movie"))
        assertNull(tmdbTitleUrl("http://192.168.1.5:8096", "Movie"))
    }

    @Test
    fun douban_links_need_a_numeric_subject() {
        assertEquals("https://movie.douban.com/subject/1292052/", doubanTitleUrl("1292052"))
        assertNull(doubanTitleUrl("tt0111161"))
        assertNull(doubanTitleUrl(null))
    }

    @Test
    fun the_meta_line_shows_what_is_known() {
        assertEquals("1994 · ★ 9.7", posterCardMeta(1994, 9.66))
        assertEquals("1994", posterCardMeta(1994, null))
        assertEquals("★ 8.0", posterCardMeta(null, 8.0))
        assertEquals("★ 10.0", posterCardMeta(null, 12.0))
        assertNull(posterCardMeta(null, 0.0))
        assertNull(posterCardMeta(12, Double.NaN))
    }

    @Test
    fun the_caption_carries_public_links_and_never_the_server() {
        val caption =
            posterCardCaption(
                PosterShareCard(
                    title = " 肖申克的救赎 ",
                    year = 1994,
                    rating = 9.7,
                    posterUrl = "http://192.168.1.5:8096/Items/abc/Images/Primary?api_key=secret",
                    tmdbId = "278",
                    mediaType = "Movie",
                    doubanId = "1292052",
                ),
            )
        assertEquals(
            "《肖申克的救赎》 1994 · ★ 9.7\n" +
                "TMDB：https://www.themoviedb.org/movie/278\n" +
                "豆瓣：https://movie.douban.com/subject/1292052/",
            caption,
        )
        assertFalse(caption.contains("192.168"))
        assertFalse(caption.contains("api_key"))
    }

    @Test
    fun a_card_with_nothing_but_a_title_is_just_the_title() {
        val caption = posterCardCaption(PosterShareCard(title = "雾港"))
        assertEquals("《雾港》", caption)
        assertTrue(caption.lines().size == 1)
    }
}
