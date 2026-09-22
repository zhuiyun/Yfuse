package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbImagesTest {
    @Test
    fun full_schedule_artwork_urls_are_not_prefixed_with_a_tmdb_host() {
        val poster = "https://posters.example/show.jpg"
        assertEquals(poster, TmdbImages.poster(poster, "w185"))
        assertEquals(poster, TmdbImages.media(poster, "w185"))
        assertEquals(poster, TmdbImages.backdrop(poster))
    }

    @Test
    fun relative_paths_keep_both_cdn_candidates_and_blank_paths_are_ignored() {
        assertEquals("https://image.tmdb.org/t/p/w185/show.jpg", TmdbImages.poster("/show.jpg", "w185"))
        assertEquals("https://media.themoviedb.org/t/p/w185/show.jpg", TmdbImages.media("/show.jpg", "w185"))
        assertNull(TmdbImages.poster(" "))
        assertNull(TmdbImages.poster(null))
        assertNull(TmdbImages.poster("//posters.example/show.jpg"))
    }
}
