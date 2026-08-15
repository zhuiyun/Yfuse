package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeStripTest {
    @Test
    fun episode_still_precedes_series_poster() {
        val card = episodeCard(stillUrl = "still", posterUrl = "poster")

        assertEquals(listOf("still", "poster"), card.artworkUrls())
    }

    @Test
    fun missing_episode_still_falls_back_to_series_poster() {
        val card = episodeCard(stillUrl = null, posterUrl = "poster")

        assertEquals(listOf(null, "poster"), card.artworkUrls())
    }

    private fun episodeCard(
        stillUrl: String?,
        posterUrl: String?,
    ) = EpisodeCard(
        title = "第 1 集",
        caption = "S1E1",
        stillUrl = stillUrl,
        posterUrl = posterUrl,
        progress = null,
        watchKey = "episode-1",
        watchMatchKeys = listOf("episode-1"),
    )
}
