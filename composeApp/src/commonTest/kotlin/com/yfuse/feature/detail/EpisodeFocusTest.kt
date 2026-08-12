package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeFocusTest {

    @Test
    fun selected_episode_is_the_scroll_target() {
        val episodes = listOf(
            episode(id = "e1", resumePositionTicks = 10_000_000L),
            episode(id = "e2"),
            episode(id = "e3"),
        )

        assertEquals(2, episodeFocusIndex(episodes, currentEpisodeId = "e3"))
    }

    @Test
    fun in_progress_episode_is_used_while_selection_is_not_in_the_loaded_season() {
        val episodes = listOf(
            episode(id = "e1", played = true),
            episode(id = "e2", playedPercentage = 42.0, resumePositionTicks = 20_000_000L),
            episode(id = "e3"),
        )

        assertEquals(1, episodeFocusIndex(episodes, currentEpisodeId = "another-season"))
    }

    @Test
    fun completed_episode_is_not_treated_as_in_progress() {
        val episodes = listOf(
            episode(
                id = "e1",
                played = true,
                playedPercentage = 100.0,
                resumePositionTicks = 40_000_000L,
            ),
            episode(id = "e2"),
        )

        assertEquals(-1, episodeFocusIndex(episodes, currentEpisodeId = null))
    }

    private fun episode(
        id: String,
        played: Boolean = false,
        playedPercentage: Double? = null,
        resumePositionTicks: Long? = null,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = null,
        seasonNumber = null,
        seasonId = null,
        overview = null,
        runtimeMinutes = null,
        primaryTag = null,
        playedPercentage = playedPercentage,
        played = played,
        resumePositionTicks = resumePositionTicks,
    )
}
