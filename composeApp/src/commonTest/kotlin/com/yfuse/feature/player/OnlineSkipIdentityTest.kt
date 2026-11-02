package com.yfuse.feature.player

import com.yfuse.core.network.TheOtherDbQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnlineSkipIdentityTest {
    private val item = PlayerMediaItem("one", "url", "url", "剧", seasonNumber = 1, episodeNumber = 2, seriesId = "show")

    @Test
    fun series_tmdb_and_coordinates_are_used_instead_of_episode_owned_id() {
        val episode = item.copy(seriesKey = "provider:tmdb:226045", providerIds = mapOf("Tmdb" to "99999"))
        assertEquals(TheOtherDbQuery(226045, 1, 2), episode.onlineSkipQuery())
        assertNull(item.copy(providerIds = mapOf("Tmdb" to "99999")).onlineSkipQuery())
    }

    @Test
    fun alternate_show_identity_and_widget_series_identity_are_supported() {
        assertEquals(
            TheOtherDbQuery(226045, 1, 2),
            item.copy(matchKeys = listOf("imdb:tt1/s1e2", "tmdb:226045/s1e2")).onlineSkipQuery(),
        )
        assertEquals(
            TheOtherDbQuery(226045, 1, 2),
            item.copy(seriesId = null, seriesKey = "tmdb:226045").onlineSkipQuery(),
        )
        assertNull(item.copy(seasonNumber = null, seriesKey = "tmdb:226045").onlineSkipQuery())
    }

    @Test
    fun a_film_needs_exact_catalog_identity() {
        val movie = PlayerMediaItem("movie", "url", "url", "片名", mediaType = "Movie")
        assertEquals(TheOtherDbQuery(603), movie.copy(providerIds = mapOf("Tmdb" to "603")).onlineSkipQuery())
        assertNull(movie.onlineSkipQuery())
    }
}
