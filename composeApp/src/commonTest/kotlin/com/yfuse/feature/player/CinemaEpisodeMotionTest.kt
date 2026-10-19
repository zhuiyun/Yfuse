package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CinemaEpisodeMotionTest {
    @Test
    fun first_open_metadata_refresh_and_engine_retry_do_not_announce_an_episode_change() {
        val first = CinemaEpisodeTitle("server-a" to "episode-1", "第一集")
        assertNull(cinemaEpisodeChange(null, first))
        assertNull(cinemaEpisodeChange(first, null))
        assertNull(cinemaEpisodeChange(first, first.copy(title = "更新后的标题")))
    }

    @Test
    fun same_title_and_same_id_on_different_servers_are_distinct_queue_entries() {
        val first = CinemaEpisodeTitle("server-a" to "episode-1", "未命名")
        val next = CinemaEpisodeTitle("server-a" to "episode-2", "未命名")
        val otherServer = CinemaEpisodeTitle("server-b" to "episode-2", "未命名")
        assertEquals(next, assertNotNull(cinemaEpisodeChange(first, next)).to)
        val latest = assertNotNull(cinemaEpisodeChange(next, otherServer))
        assertEquals(next, latest.from)
        assertEquals(otherServer, latest.to)
    }
}
