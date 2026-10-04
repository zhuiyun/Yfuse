package com.yfuse.feature.player

import com.yfuse.core.model.ShortDramaMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeSwipeTest {
    private val height = 2_000f

    @Test
    fun a_long_enough_drag_up_is_the_next_episode_and_down_the_one_before() {
        assertEquals(EpisodeSwipe.Next, episodeSwipe(-300f, height, hasNext = true, hasPrevious = true))
        assertEquals(EpisodeSwipe.Previous, episodeSwipe(300f, height, hasNext = true, hasPrevious = true))
        assertEquals(EpisodeSwipe.None, episodeSwipe(-200f, height, hasNext = true, hasPrevious = true))
    }

    @Test
    fun there_is_nowhere_to_go_past_either_end_of_the_queue() {
        assertEquals(EpisodeSwipe.None, episodeSwipe(-300f, height, hasNext = false, hasPrevious = true))
        assertEquals(EpisodeSwipe.None, episodeSwipe(300f, height, hasNext = true, hasPrevious = false))
        assertEquals(EpisodeSwipe.None, episodeSwipe(-300f, 0f, hasNext = true, hasPrevious = true))
    }

    @Test
    fun the_viewers_short_drama_mode_decides_before_the_picture_does() {
        // 自动 swipes an upright picture only; 短剧 and 普通剧集 settle it whatever the picture.
        assertTrue(ShortDramaMode.Auto.swipesEpisodes(decodedPortraitPicture = true))
        assertFalse(ShortDramaMode.Auto.swipesEpisodes(decodedPortraitPicture = false))
        assertFalse(ShortDramaMode.Auto.swipesEpisodes(decodedPortraitPicture = null))
        for (picture in listOf(true, false, null)) {
            assertTrue(ShortDramaMode.On.swipesEpisodes(picture))
            assertFalse(ShortDramaMode.Off.swipesEpisodes(picture))
        }
    }

    @Test
    fun short_drama_mode_is_remembered_per_series_or_per_folder() {
        val video = PlayerMediaItem("v1", "https://emby.example/Videos/v1/stream", "", "01", serverId = "s1")
        assertEquals("series-9", video.copy(seriesId = "series-9", folderId = "folder-3").shortDramaKey())
        assertEquals("folder:folder-3", video.copy(folderId = "folder-3").shortDramaKey())
        // A film or a lone video has no run of episodes to switch.
        assertNull(video.shortDramaKey())
    }

    @Test
    fun the_sides_keep_brightness_and_volume() {
        val width = 1_000f
        assertTrue(inEpisodeSwipeBand(500f, width, minEdgePx = 150f))
        assertFalse(inEpisodeSwipeBand(150f, width, minEdgePx = 150f))
        assertFalse(inEpisodeSwipeBand(900f, width, minEdgePx = 150f))
        // A narrow picture keeps at least the minimum band.
        assertFalse(inEpisodeSwipeBand(170f, width, minEdgePx = 180f))
    }
}
