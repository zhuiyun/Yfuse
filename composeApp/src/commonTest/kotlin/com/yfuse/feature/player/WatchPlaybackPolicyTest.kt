package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPlaybackPolicyTest {

    @Test
    fun nudge_rate_is_not_promoted_to_the_room_nominal_rate() {
        assertEquals(1f, nominalWatchRate(measured = 1.02f, room = 1f))
        assertEquals(1f, nominalWatchRate(measured = 0.98f, room = 1f))
        assertEquals(1.5f, nominalWatchRate(measured = 1.5f, room = 1f))
    }

    @Test
    fun unmatched_media_warns_only_after_the_grace_ticks_and_clears_on_match() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items = listOf(
            PlayerMediaItem("episode", "direct", "transcode", "第一集", watchKey = "tmdb:1"),
        )

        repeat(2) {
            assertNull(matcher.resolve(items, "tmdb:missing"))
            assertNull(warning)
        }
        assertNull(matcher.resolve(items, "tmdb:missing"))
        assertEquals("房间在播放你的媒体库里没有的内容，无法同步进度", warning)

        assertEquals(0, matcher.resolve(items, "tmdb:1"))
        assertNull(warning)
    }
}
