package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun the_number_grid_reads_episode_numbers_and_marks_what_was_watched() {
        val cards =
            listOf(
                episodeCard(stillUrl = null, posterUrl = null, number = 7, progress = 1f, key = "a"),
                episodeCard(stillUrl = null, posterUrl = null, number = 8, progress = 0.4f, key = "b"),
                episodeCard(stillUrl = null, posterUrl = null, number = null, progress = null, key = ""),
            )

        val cells = cards.toNumberCells(currentIndex = 1)

        assertEquals(listOf(7, 8, 3), cells.map { it.number })
        assertEquals(listOf(true, false, false), cells.map { it.watched })
        // A finished episode shows as watched, not as a full bar.
        assertNull(cells[0].progress)
        assertEquals(0.4f, cells[1].progress)
        assertEquals(listOf(false, true, false), cells.map { it.current })
        assertEquals("episode-2", cells[2].key)
    }

    private fun episodeCard(
        stillUrl: String?,
        posterUrl: String?,
        number: Int? = null,
        progress: Float? = null,
        key: String = "episode-1",
    ) = EpisodeCard(
        title = "第 1 集",
        caption = "S1E1",
        stillUrl = stillUrl,
        posterUrl = posterUrl,
        progress = progress,
        watchKey = key,
        watchMatchKeys = listOf(key),
        number = number,
    )
}
