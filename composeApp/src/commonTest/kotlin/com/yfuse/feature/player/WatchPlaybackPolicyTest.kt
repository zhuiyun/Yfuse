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

    /**
     * The two libraries can name one show differently — the host's holds its Tmdb id, the
     * guest's only its Tvdb id — and a key compared as a string then never matched: the
     * room stayed on 房主控制播放 forever and the host's controls stopped at the guest.
     * Inside a queue that is already this show, the coordinate settles it.
     */
    @Test
    fun an_episode_matches_when_the_libraries_name_the_show_differently() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items = listOf(
            PlayerMediaItem(
                "e4", "direct", "transcode", "第 4 集",
                seasonNumber = 2,
                episodeNumber = 4,
                watchKey = "tvdb:121361/s2e4",
            ),
            PlayerMediaItem(
                "e5", "direct", "transcode", "第 5 集",
                seasonNumber = 2,
                episodeNumber = 5,
                watchKey = "tvdb:121361/s2e5",
            ),
        )

        assertEquals(1, matcher.resolve(items, "tmdb:1399/s2e5"))
        assertNull(warning)
    }

    /** The fallback is a coordinate within one show, not a licence to match anything. */
    @Test
    fun a_coordinate_the_queue_does_not_hold_still_warns() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items = listOf(
            PlayerMediaItem(
                "e5", "direct", "transcode", "第 5 集",
                seasonNumber = 2,
                episodeNumber = 5,
                watchKey = "tmdb:1399/s2e5",
            ),
        )

        repeat(3) { assertNull(matcher.resolve(items, "tmdb:1399/s3e1")) }

        assertEquals("房间在播放你的媒体库里没有的内容，无法同步进度", warning)
    }
}
