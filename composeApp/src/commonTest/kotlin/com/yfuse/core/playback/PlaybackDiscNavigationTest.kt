package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertEquals("05:15", state.selectedChapter?.timeLabel)
    }

    @Test
    fun multi_angle_titles_expose_zero_based_selection_with_human_labels() {
        val state =
            PlaybackDiscNavigationState(
                kind = PlaybackDiscKind.BluRay,
                angleCount = 3,
                selectedAngleIndex = 1,
            )

        assertTrue(state.available)
        assertEquals(3, state.effectiveAngleCount)
        assertEquals(listOf("视角 1", "视角 2", "视角 3"), state.angleOptions.map { it.label })
        assertEquals("视角 2", state.selectedAngle?.label)
    }

    @Test
    fun explicit_mpls_hints_become_stable_playlist_numbers() {
        assertEquals(1, mplsPlaylistNumber("BDMV/PLAYLIST/00001.mpls"))
        assertEquals(802, mplsPlaylistNumber("mpls/00802"))
        assertEquals(42, mplsPlaylistNumber("MPLS: 00042"))
        assertNull(mplsPlaylistNumber("Feature 2026"))

        val title = PlaybackDiscTitle(index = 0, playlistNumber = 802)
        assertEquals("00802.mpls", title.playlistLabel)
        assertEquals("00802.mpls", title.label)
    }

    @Test
    fun long_chapter_times_use_hour_format() {
        val chapter = PlaybackDiscChapter(index = 4, startMs = 7_445_000L)

        assertEquals("章节 5", chapter.label)
        assertEquals("02:04:05", chapter.timeLabel)
    }

    @Test
    fun ordinary_media_has_no_disc_navigation() {
        val state = PlaybackDiscNavigationState()

        assertFalse(state.available)
        assertTrue(state.titleOptions.isEmpty())
        assertTrue(state.chapterOptions.isEmpty())
        assertTrue(state.angleOptions.isEmpty())
    }
}
