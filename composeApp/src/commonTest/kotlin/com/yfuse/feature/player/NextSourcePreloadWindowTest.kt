package com.yfuse.feature.player

import com.yfuse.core.model.Episode
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextSourcePreloadWindowTest {
    @Test
    fun the_next_episode_is_prepared_over_the_last_ninety_seconds_or_half_a_short_one() {
        assertEquals(90_000L, nextSourcePreloadWindowMs(600_000L))
        assertEquals(45_000L, nextSourcePreloadWindowMs(90_000L))
        assertEquals(90_000L, nextSourcePreloadWindowMs(0L))
    }

    @Test
    fun a_prepared_next_episode_is_held_until_this_one_ends() {
        assertEquals(60_000L, nextSourceHoldMs(45_000L))
        assertEquals(PREPARED_SOURCE_MIN_HOLD_MS, nextSourceHoldMs(5_000L))
        assertEquals(PREPARED_SOURCE_MAX_HOLD_MS, nextSourceHoldMs(300_000L))
    }

    @Test
    fun a_short_episode_refreshes_the_queue_only_near_its_tail() {
        assertTrue(queueRefreshDue(remainingMs = 240_000L, durationMs = 2_400_000L, itemsAfterCurrent = 40))
        assertFalse(queueRefreshDue(remainingMs = 60_000L, durationMs = 120_000L, itemsAfterCurrent = 40))
        assertTrue(queueRefreshDue(remainingMs = 60_000L, durationMs = 120_000L, itemsAfterCurrent = 2))
        assertFalse(queueRefreshDue(remainingMs = 600_000L, durationMs = 2_400_000L, itemsAfterCurrent = 2))
        assertFalse(queueRefreshDue(remainingMs = 0L, durationMs = 2_400_000L, itemsAfterCurrent = 2))
    }

    @Test
    fun the_queue_leaves_out_missing_episodes_and_specials_unless_a_special_is_playing() {
        val special = episode("s0e1", season = 0)
        val first = episode("s1e1", season = 1)
        val missing = episode("s1e2", season = 1, missing = true)
        val second = episode("s1e3", season = 1)
        val season = listOf(special, first, missing, second)

        assertEquals(listOf(first, second), season.queueEpisodes(currentId = "s1e1"))
        assertEquals(listOf(special, first, second), season.queueEpisodes(currentId = "s0e1"))
        // What is playing always stays, even if the server now calls it missing.
        assertEquals(listOf(first, missing, second), season.queueEpisodes(currentId = "s1e2"))
    }

    private fun episode(
        id: String,
        season: Int,
        missing: Boolean = false,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = 1,
        seasonNumber = season,
        seasonId = "season-$season",
        overview = null,
        runtimeMinutes = null,
        primaryTag = null,
        playedPercentage = null,
        resumePositionTicks = null,
        missing = missing,
    )

    @Test
    fun fetched_media_segments_fill_in_only_where_an_item_has_none() {
        val intro = PlaybackSegment(PlaybackSegmentType.Intro, 0L, 20_000L)
        val cache = mapOf(mediaSegmentKey("jf", "e2") to listOf(intro))
        val bare = PlayerMediaItem(id = "e2", url = "u", transcodeUrl = "", title = "E2", serverId = "jf")
        val marked = bare.copy(playbackSegments = listOf(PlaybackSegment(PlaybackSegmentType.Intro, 5_000L, 9_000L)))

        assertEquals(listOf(intro), bare.withMediaSegments(cache).playbackSegments)
        assertEquals(marked, marked.withMediaSegments(cache))
        // The same item id on another server is another item.
        assertEquals(bare.copy(serverId = "emby"), bare.copy(serverId = "emby").withMediaSegments(cache))
    }
}
