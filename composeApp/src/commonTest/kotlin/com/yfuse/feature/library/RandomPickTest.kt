package com.yfuse.feature.library

import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

class RandomPickTest {
    private fun item(
        id: String,
        title: String,
        type: String,
        rating: Double,
        played: Boolean,
    ) = MediaItem(
        id = id,
        title = title,
        subtitle = null,
        type = type,
        posterItemId = id,
        posterTag = null,
        backdropItemId = id,
        backdropTag = null,
        playedPercentage = null,
        communityRating = rating,
        played = played,
    )

    private val items =
        listOf(
            item(id = "1", title = "A", type = "Movie", rating = 8.4, played = false),
            item(id = "2", title = "B", type = "Movie", rating = 6.2, played = true),
            item(id = "3", title = "C", type = "Series", rating = 9.0, played = true),
        )

    @Test
    fun filters_high_rated_and_unplayed_candidates() {
        assertEquals(listOf("1", "2", "3"), filterRandomPickCandidates(items, RandomPickFilter.All).map { it.id })
        assertEquals(listOf("1", "3"), filterRandomPickCandidates(items, RandomPickFilter.HighRated).map { it.id })
        assertEquals(listOf("1"), filterRandomPickCandidates(items, RandomPickFilter.Unplayed).map { it.id })
    }
}
