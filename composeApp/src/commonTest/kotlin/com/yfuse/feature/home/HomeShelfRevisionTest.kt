package com.yfuse.feature.home

import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HomeShelfRevisionTest {
    @Test
    fun a_refresh_that_brings_back_the_same_posters_is_not_new_content() {
        val before = listOf(TmdbRow("热门", listOf(item(1, rating = 7.1), item(2))))
        val after = listOf(TmdbRow("热门", listOf(item(1, rating = 7.4), item(2))))

        assertEquals(homeShelfRevision(before), homeShelfRevision(after))
    }

    @Test
    fun a_new_or_reordered_poster_is_new_content() {
        val before = homeShelfRevision(listOf(TmdbRow("热门", listOf(item(1), item(2)))))

        assertNotEquals(before, homeShelfRevision(listOf(TmdbRow("热门", listOf(item(2), item(1))))))
        assertNotEquals(before, homeShelfRevision(listOf(TmdbRow("热门", listOf(item(1), item(3))))))
        assertNotEquals(before, homeShelfRevision(listOf(TmdbRow("正在上映", listOf(item(1), item(2))))))
    }

    @Test
    fun posters_past_what_a_shelf_shows_do_not_count() {
        val shown = (1..12).map { item(it) }

        assertEquals(
            homeShelfRevision(listOf(TmdbRow("热门", shown + item(13)))),
            homeShelfRevision(listOf(TmdbRow("热门", shown + item(14)))),
        )
    }

    private fun item(
        id: Int,
        rating: Double? = null,
    ): TmdbItem =
        TmdbItem(
            id = id,
            title = "影片 $id",
            overview = null,
            posterPath = null,
            backdropPath = null,
            year = null,
            mediaType = "movie",
            rating = rating,
        )
}
