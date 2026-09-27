package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeMarkTest {
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
    fun onlyTheNamedEpisodesChangeAndTheirResumePointGoes() {
        val episodes = listOf(episode("e1", false, position = 100L), episode("e2", false, position = 200L))
        val marked = episodesMarked(episodes, setOf("e1"), played = true)
        assertTrue(marked[0].played)
        assertNull(marked[0].resumePositionTicks)
        assertNull(marked[0].playedPercentage)
        assertEquals(episodes[1], marked[1])
        val unmarked = episodesMarked(marked, setOf("e1"), played = false)
        assertFalse(unmarked[0].played)
    }

    @Test
    fun aSingleEpisodeSaysNothingSeveralAreCountedAndQueuedWritesAreNamed() {
        assertNull(episodesMarkedMessage(count = 1, played = true, queued = 0))
        assertEquals("已将 4 集标记为已看", episodesMarkedMessage(count = 4, played = true, queued = 0))
        assertEquals("已将 2 集标记为未看", episodesMarkedMessage(count = 2, played = false, queued = 0))
        assertEquals("已更新 1 集，1 项将在服务器恢复后同步", episodesMarkedMessage(count = 1, played = true, queued = 1))
    }
}
