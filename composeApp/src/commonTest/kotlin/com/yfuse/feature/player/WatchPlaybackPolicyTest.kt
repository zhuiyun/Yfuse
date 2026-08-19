package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPlaybackPolicyTest {
    @Test
    fun initial_autoplay_buffering_is_not_published_as_a_room_pause() {
        assertFalse(watchTimelinePaused(playbackRequested = true, ended = false))
        assertTrue(watchTimelinePaused(playbackRequested = false, ended = false))
        assertTrue(watchTimelinePaused(playbackRequested = true, ended = true))
    }

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
        val items =
            listOf(
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
        val items =
            listOf(
                PlayerMediaItem(
                    "e4",
                    "direct",
                    "transcode",
                    "第 4 集",
                    seasonNumber = 2,
                    episodeNumber = 4,
                    watchKey = "tvdb:121361/s2e4",
                ),
                PlayerMediaItem(
                    "e5",
                    "direct",
                    "transcode",
                    "第 5 集",
                    seasonNumber = 2,
                    episodeNumber = 5,
                    watchKey = "tvdb:121361/s2e5",
                ),
            )

        assertEquals(1, matcher.resolve(items, "tmdb:1399/s2e5"))
        assertNull(warning)
    }

    /**
     * A film has no coordinate to fall back on, so its whole defence is answering to every
     * name its own library can justify. Host holds only Imdb for it and publishes that; the
     * guest holds both and prefers Tmdb.
     */
    @Test
    fun a_film_matches_on_any_provider_id_the_two_libraries_share() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items =
            listOf(
                PlayerMediaItem(
                    "film",
                    "direct",
                    "transcode",
                    "黑客帝国",
                    watchKey = "tmdb:603",
                    matchKeys = listOf("tmdb:603", "imdb:tt0133093", "emby:local"),
                ),
            )

        assertEquals(0, matcher.resolve(items, "imdb:tt0133093"))
        assertNull(warning)
    }

    /**
     * The two libraries share no metadata at all: neither names the show, so the published
     * key is server-local apart from the coordinate. Both people opened the same series by
     * hand, and that coordinate is the only thing left to sync on.
     */
    @Test
    fun an_episode_matches_when_neither_library_can_name_the_show() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items =
            listOf(
                PlayerMediaItem(
                    "guest-e5",
                    "direct",
                    "transcode",
                    "第 5 集",
                    seasonNumber = 2,
                    episodeNumber = 5,
                    watchKey = "emby:guest-e5/s2e5",
                    matchKeys = listOf("emby:guest-e5/s2e5", "emby:guest-e5"),
                ),
            )

        assertEquals(0, matcher.resolve(items, "emby:host-e5/s2e5"))
        assertNull(warning)
    }

    /**
     * A coordinate is not a licence to follow anything: when both sides do name a show and
     * name different ones, s2e5 is provably a different episode. A guest who wandered into
     * another series should be told it is out of sync, not yanked to episode five of it.
     */
    @Test
    fun a_coordinate_from_a_different_show_is_refused() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items =
            listOf(
                PlayerMediaItem(
                    "other-e5",
                    "direct",
                    "transcode",
                    "别的剧 第 5 集",
                    seasonNumber = 2,
                    episodeNumber = 5,
                    watchKey = "tmdb:456/s2e5",
                    matchKeys = listOf("tmdb:456/s2e5", "emby:other-e5"),
                ),
            )

        repeat(3) { assertNull(matcher.resolve(items, "tmdb:1399/s2e5")) }

        assertEquals("房间在播放你的媒体库里没有的内容，无法同步进度", warning)
    }

    /** The fallback is a coordinate within one show, not a licence to match anything. */
    @Test
    fun a_coordinate_the_queue_does_not_hold_still_warns() {
        var warning: String? = null
        val matcher = WatchMediaMatcher { warning = it }
        val items =
            listOf(
                PlayerMediaItem(
                    "e5",
                    "direct",
                    "transcode",
                    "第 5 集",
                    seasonNumber = 2,
                    episodeNumber = 5,
                    watchKey = "tmdb:1399/s2e5",
                ),
            )

        repeat(3) { assertNull(matcher.resolve(items, "tmdb:1399/s3e1")) }

        assertEquals("房间在播放你的媒体库里没有的内容，无法同步进度", warning)
    }
}
