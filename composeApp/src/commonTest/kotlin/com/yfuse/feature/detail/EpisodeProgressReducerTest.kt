package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeProgressReducerTest {
    private fun episode(
        id: String,
        played: Boolean,
        position: Long? = null,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = id.removePrefix("e").toInt(),
        seasonNumber = 1,
        seasonId = "s1",
        overview = null,
        runtimeMinutes = 45,
        primaryTag = null,
        playedPercentage = position?.let { 25.0 },
        played = played,
        resumePositionTicks = position,
    )

    @Test
    fun progress_manager_selection_and_apply_are_reduced_atomically() {
        val original =
            DetailState(
                episodes =
                    listOf(
                        episode("e1", played = false, position = 100L),
                        episode("e2", played = true),
                    ),
            )
        val opened = with(DetailReducer) { original.reduce(DetailMsg.ProgressManagerOpened) }
        val selected =
            with(DetailReducer) {
                opened.reduce(DetailMsg.ProgressSelectionChanged(setOf("e1")))
            }
        val applied =
            with(DetailReducer) {
                selected.reduce(
                    DetailMsg.EpisodesProgressChanged(
                        episodeIds = setOf("e1"),
                        played = true,
                        message = "已更新",
                    ),
                )
            }

        assertTrue(opened.progressManagerOpen)
        assertEquals(setOf("e1"), selected.progressSelection)
        assertTrue(applied.episodes.first().played)
        assertEquals(null, applied.episodes.first().resumePositionTicks)
        assertFalse(applied.progressManagerOpen)
        assertEquals("已更新", applied.actionMessage)
    }
}
