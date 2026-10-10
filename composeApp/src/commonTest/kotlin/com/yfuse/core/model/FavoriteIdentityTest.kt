package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteIdentityTest {
    @Test
    fun duplicatesFromDifferentPagesUseNormalizedProviderIds() {
        val a = item("a", mapOf("Tmdb" to " 603 ", "Imdb" to "TT0133093"))
        val b = item("b", mapOf("tmdb" to "603"))
        assertEquals(listOf(a), deduplicateFavoriteItems(listOf(a, b, a)))
    }

    @Test
    fun conflictingIdsUnknownTitlesAndEpisodeCoordinatesRemainSeparate() {
        val a = item("a", mapOf("Tmdb" to "603", "Imdb" to "tt1"))
        val conflict = item("b", mapOf("Tmdb" to "603", "Imdb" to "tt2"))
        val unknown = item("c", emptyMap())
        val unknown2 = unknown.copy(id = "d", posterItemId = "d")
        val episode = item("e1", mapOf("Tmdb" to "1")).copy(type = "Episode")
        val episode2 = episode.copy(id = "e2", providerIds = mapOf("Tmdb" to "2"))
        val rows = listOf(a, conflict, unknown, unknown2, episode, episode2)
        assertEquals(rows, deduplicateFavoriteItems(rows))
    }

    private fun item(
        id: String,
        ids: Map<String, String>,
    ) = MediaItem(
        id = id,
        title = "同名影片",
        subtitle = null,
        type = "Movie",
        posterItemId = id,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
        providerIds = ids,
    )
}
