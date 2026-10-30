package com.yfuse.core.model

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.TmdbHomeCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TmdbFeaturedTest {
    private val date = "2026-10-26"

    @Test
    fun the_whole_group_changes_daily_and_stays_stable_within_a_day() {
        val home = home()
        val first = home.featuredSlidesForDay(date)
        val next = home.featuredSlidesForDay("2026-10-27")
        assertEquals(8, first.size)
        assertEquals(first, home.featuredSlidesForDay(date))
        assertEquals(3, first.count { it.id >= 100 })
        assertTrue(
            first
                .map { it.id }
                .toSet()
                .intersect(next.map { it.id }.toSet())
                .isEmpty(),
        )
    }

    @Test
    fun popular_only_also_rotates_an_entire_group() {
        val home = home().copy(rows = emptyList())
        val first = home.featuredSlidesForDay(date)
        val next = home.featuredSlidesForDay("2026-10-27")
        assertEquals(8, first.size)
        assertTrue(first.toSet().intersect(next.toSet()).isEmpty())
    }

    @Test
    fun recent_only_and_small_pools_fill_without_repeating_titles() {
        val recentOnly = home().copy(featured = emptyList())
        assertEquals(8, recentOnly.featuredSlidesForDay(date).size)
        val small = TmdbHome(featured = (1..8).map(::item))
        assertEquals(8, small.featuredSlidesForDay(date).distinct().size)
        assertNotEquals(small.featuredSlidesForDay(date), small.featuredSlidesForDay("2026-10-27"))
        assertEquals(listOf(item(1)), TmdbHome(featured = listOf(item(1))).featuredSlidesForDay(date))
        assertTrue(TmdbHome().featuredSlidesForDay(date).isEmpty())
    }

    @Test
    fun excludes_old_future_and_missing_artwork_from_the_recent_mix() {
        val invalid =
            listOf(
                item(101).copy(releaseDate = "2026-09-25"),
                item(102).copy(releaseDate = "2026-10-27"),
                item(103).copy(releaseDate = null),
                item(104).copy(backdropPath = ""),
                item(105).copy(backdropPath = null),
            )
        val home = home().copy(rows = listOf(TmdbRow(TmdbRowTitles.LATEST, invalid)))
        assertEquals(8, home.featuredSlidesForDay(date).size)
        assertTrue(home.featuredSlidesForDay(date).all { it.id < 100 })
    }

    @Test
    fun deduplicates_overlapping_feeds_but_keeps_movie_and_tv_with_the_same_id() {
        val movie = item(1)
        val tv = movie.copy(mediaType = "tv")
        val home =
            TmdbHome(
                featured = listOf(movie, movie, tv),
                rows = listOf(TmdbRow(TmdbRowTitles.LATEST, listOf(movie, tv, movie))),
            )
        assertEquals(setOf(movie, tv), home.featuredSlidesForDay(date).toSet())
        assertEquals(2, home.featuredSlidesForDay(date).size)
    }

    @Test
    fun restoring_the_cache_preserves_the_daily_group() {
        val home = home().copy(rows = listOf(TmdbRow(TmdbRowTitles.LATEST, (100..139).map(::item))))
        val cache = TmdbHomeCache(MapSettings()) { date }
        cache.write(home)
        assertEquals(home.featuredSlidesForDay(date), cache.read()!!.featuredSlidesForDay(date))
    }

    @Test
    fun manual_batches_change_within_a_day_and_keep_recent_release_mix() {
        val content = home()
        val first = content.featuredSlidesForDay(date)
        val history = first.map { "${it.mediaType}:${it.id}" }
        val second = content.featuredSlidesForDay(date, history)
        assertEquals(8, second.size)
        assertEquals(3, second.count { it.id >= 100 })
        assertTrue(first.toSet().intersect(second.toSet()).isEmpty())
        assertEquals(second, content.featuredSlidesForDay(date, history))
    }

    private fun home() =
        TmdbHome(
            featured = (1..21).map(::item),
            rows = listOf(TmdbRow(TmdbRowTitles.LATEST, (100..120).map(::item))),
        )

    private fun item(id: Int) =
        TmdbItem(
            id = id,
            title = "Title $id",
            overview = null,
            posterPath = "/$id.jpg",
            backdropPath = "/$id-bg.jpg",
            year = "2026",
            mediaType = "movie",
            rating = 8.0,
            releaseDate = "2026-10-20",
        )
}
