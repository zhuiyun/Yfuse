package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvDiscNavigationMetadataTest {
    @Test
    fun edition_and_chapter_lists_keep_names_ids_playlists_defaults_and_times() {
        val properties =
            FakeDiscProperties(
                ints =
                    mapOf(
                        "edition-list/count" to 2,
                        "edition-list/0/id" to 101,
                        "edition-list/1/id" to 205,
                        "current-edition" to 1,
                        "chapter-list/count" to 3,
                        "chapter" to 2,
                    ),
                strings =
                    mapOf(
                        "edition-list/0/title" to "BDMV/PLAYLIST/00802.mpls",
                        "edition-list/1/title" to "Director's Cut",
                        "chapter-list/0/title" to "Opening",
                        "chapter-list/2/title" to "Finale",
                    ),
                doubles =
                    mapOf(
                        "chapter-list/0/time" to 0.0,
                        "chapter-list/1/time" to 61.25,
                        "chapter-list/2/time" to 125.5,
                    ),
                flags = mapOf("edition-list/0/default" to true),
            )

        val state =
            readMpvDiscNavigationMetadata(
                previous = PlaybackDiscNavigationState(kind = PlaybackDiscKind.BluRay),
                properties = properties,
            )

        assertEquals(2, state.titleCount)
        assertEquals(101, state.titles[0].id)
        assertEquals(802, state.titles[0].playlistNumber)
        assertEquals("00802.mpls", state.titles[0].playlistLabel)
        assertTrue(state.titles[0].isDefault)
        assertEquals("Director's Cut", state.selectedTitle?.label)
        assertEquals(3, state.chapterCount)
        assertEquals("Finale", state.selectedChapter?.label)
        assertEquals(61_250L, state.chapters[1].startMs)
        assertEquals("01:01", state.chapters[1].timeLabel)
        assertEquals(125_500L, state.chapters[2].startMs)
    }

    @Test
    fun missing_rich_lists_fall_back_to_legacy_counts() {
        val state =
            readMpvDiscNavigationMetadata(
                previous = PlaybackDiscNavigationState(kind = PlaybackDiscKind.Dvd),
                properties =
                    FakeDiscProperties(
                        ints =
                            mapOf(
                                "editions" to 2,
                                "current-edition" to 0,
                                "chapters" to 4,
                                "chapter" to 3,
                            ),
                    ),
            )

        assertEquals(listOf("标题 1", "标题 2"), state.titleOptions.map { it.label })
        assertEquals(4, state.chapterOptions.size)
        assertEquals("章节 4", state.selectedChapter?.label)
    }

    @Test
    fun invalid_times_and_indices_are_safely_bounded() {
        assertNull(mpvDiscTimeMs(Double.NaN))
        assertNull(mpvDiscTimeMs(Double.POSITIVE_INFINITY))
        assertNull(mpvDiscTimeMs(-1.0))
        assertEquals(1_234L, mpvDiscTimeMs(1.234))

        val state =
            readMpvDiscNavigationMetadata(
                previous =
                    PlaybackDiscNavigationState(
                        kind = PlaybackDiscKind.BluRay,
                        selectedTitleIndex = 99,
                        selectedChapterIndex = 99,
                    ),
                properties =
                    FakeDiscProperties(
                        ints =
                            mapOf(
                                "edition-list/count" to 1,
                                "current-edition" to -1,
                                "chapter-list/count" to 1,
                                "chapter" to -1,
                            ),
                        doubles = mapOf("chapter-list/0/time" to -10.0),
                    ),
            )

        assertEquals(0, state.selectedTitleIndex)
        assertEquals(0, state.selectedChapterIndex)
        assertNull(state.chapters.single().startMs)
        assertFalse(state.titles.single().isDefault)
    }
}

private class FakeDiscProperties(
    private val ints: Map<String, Int> = emptyMap(),
    private val doubles: Map<String, Double> = emptyMap(),
    private val strings: Map<String, String> = emptyMap(),
    private val flags: Map<String, Boolean> = emptyMap(),
) : MpvDiscPropertyReader {
    override fun int(name: String): Int? = ints[name]

    override fun double(name: String): Double? = doubles[name]

    override fun string(name: String): String? = strings[name]

    override fun flag(name: String): Boolean? = flags[name]
}
