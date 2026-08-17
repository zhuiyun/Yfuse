package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiscNavigationTest {
    @Test
    fun count_only_backends_still_expose_selectable_rows() {
        val state =
            PlaybackDiscNavigationState(
                kind = PlaybackDiscKind.BluRay,
                titleCount = 3,
                selectedTitleIndex = 1,
                chapterCount = 2,
                selectedChapterIndex = 0,
            )

        assertTrue(state.available)
        assertEquals(listOf("标题 1", "标题 2", "标题 3"), state.titleOptions.map { it.label })
        assertEquals(listOf("章节 1", "章节 2"), state.chapterOptions.map { it.label })
        assertEquals("标题 2", state.selectedTitle?.label)
        assertEquals("章节 1", state.selectedChapter?.label)
    }

    @Test
    fun rich_metadata_keeps_authored_names_and_fills_missing_rows() {
        val state =
            PlaybackDiscNavigationState(
                kind = PlaybackDiscKind.BluRay,
                titleCount = 3,
                selectedTitleIndex = 2,
                titles =
                    listOf(
                        PlaybackDiscTitle(index = 0, id = 12, title = "正片", isDefault = true),
                        PlaybackDiscTitle(index = 2, id = 28, title = "导演剪辑版"),
                    ),
                chapterCount = 2,
                selectedChapterIndex = 1,
                chapters =
                    listOf(
                        PlaybackDiscChapter(index = 0, title = "序章", startMs = 0L),
                        PlaybackDiscChapter(index = 1, title = "追逐", startMs = 315_000L),
                    ),
            )

        assertEquals(listOf("正片", "标题 2", "导演剪辑版"), state.titleOptions.map { it.label })
        assertEquals("导演剪辑版", state.selectedTitle?.label)
        assertEquals("追逐", state.selectedChapter?.label)
        assertEquals(315_000L, state.selectedChapter?.startMs)
    }

    @Test
    fun ordinary_media_has_no_disc_navigation() {
        val state = PlaybackDiscNavigationState()

        assertFalse(state.available)
        assertTrue(state.titleOptions.isEmpty())
        assertTrue(state.chapterOptions.isEmpty())
    }
}
