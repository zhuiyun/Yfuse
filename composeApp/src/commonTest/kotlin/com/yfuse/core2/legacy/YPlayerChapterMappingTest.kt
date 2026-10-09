package com.yfuse.core2.legacy

import com.yfuse.core.model.PlaybackChapter
import com.yfuse.core2.api.YChapter
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class YPlayerChapterMappingTest {
    @Test
    fun container_chapters_reach_the_progress_bar_named_and_without_a_new_list_per_tick() {
        val player = ChapterPlayer()
        player.state.value =
            YPlayerState(
                durationMs = 3_600_000L,
                chapters =
                    listOf(
                        YChapter(0L, "Chapter 01"),
                        YChapter(120_000L, ""),
                        YChapter(60_000L, "Opening"),
                        YChapter(3_700_000L, "Past the end"),
                    ),
            )
        val presented = player.asPlaybackStateFlow()

        val chapters = presented.value.chapters
        // Counting names, unnamed chapters and ones past the runtime are left out, as for a server's.
        assertEquals(listOf(PlaybackChapter(60_000L, "Opening")), chapters)

        player.state.value = player.state.value.copy(positionMs = 1_000L)
        assertSame(chapters, presented.value.chapters)
    }

    private class ChapterPlayer : YPlayer {
        override val state = MutableStateFlow(YPlayerState())

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun selectTrack(
            type: YTrackType,
            id: String,
        ) = Unit

        override fun selectItem(index: Int) = Unit

        override fun retry() = Unit

        override fun release() = Unit
    }
}
