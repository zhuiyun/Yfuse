package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterProgressMarkersTest {
    private val chapters =
        listOf(
            PlaybackChapter(0L, "序幕"),
            PlaybackChapter(600_000L, "灯塔"),
            PlaybackChapter(1_800_000L, "回声"),
        )

    /** The chapter markers of an hour-long file with no skip boundaries. */
    private fun chapterMarkersOf(chapters: List<PlaybackChapter>): List<PlaybackProgressMarker> =
        playbackProgressMarkers(SkipSegmentState(), 3_600_000L, chapters.asProgressChapters()).chapterMarkers()

    @Test
    fun fileChaptersJoinTheSkipMarkersAsChapterMarkers() {
        val markers =
            playbackProgressMarkers(
                skip = SkipSegmentState(introStartSeconds = 30L, introEndSeconds = 90L, creditsLeadSeconds = 120L),
                durationMs = 3_600_000L,
                chapters = chapters.asProgressChapters(),
            )

        assertEquals(
            listOf(0L, 30_000L, 90_000L, 600_000L, 1_800_000L, 3_480_000L),
            markers.map(PlaybackProgressMarker::positionMs),
        )
        assertEquals(listOf("序幕", "灯塔", "回声"), markers.chapterMarkers().map { it.label })
        // 片头 and 片尾 stay ticks; only the chapters cut the rail.
        assertTrue(markers.filter { it.label == "片头" || it.label == "片尾" }.none { it.chapter })
    }

    @Test
    fun theChapterIsTheLastOneToStartAtOrBeforeThePosition() {
        val markers = chapterMarkersOf(chapters)

        assertEquals(0, chapterIndexAt(markers, 0L))
        assertEquals(0, chapterIndexAt(markers, 599_999L))
        assertEquals(1, chapterIndexAt(markers, 600_000L))
        assertEquals(2, chapterIndexAt(markers, 3_599_000L))
        assertEquals("灯塔", chapterNameAt(markers, 1_000_000L))
    }

    @Test
    fun beforeTheFirstChapterThereIsNoName() {
        val markers = chapterMarkersOf(listOf(PlaybackChapter(120_000L, "正片")))

        assertEquals(-1, chapterIndexAt(markers, 60_000L))
        assertNull(chapterNameAt(markers, 60_000L))
        assertNull(chapterNameAt(emptyList(), 60_000L))
    }

    @Test
    fun theRailIsCutOnlyInsideItself() {
        val markers = chapterMarkersOf(chapters)

        assertEquals(
            listOf(600_000f / 3_600_000f, 1_800_000f / 3_600_000f),
            chapterBoundaryFractions(markers, 3_600_000L),
        )
        assertEquals(emptyList(), chapterBoundaryFractions(markers, 0L))
    }

    @Test
    fun theMagnetKeepsItsReachWhereChaptersAreSparse() {
        // 14 dp on a 700 px rail, chapters a sixth of the rail apart.
        assertEquals(14f / 700f, seekMagnetFraction(14f, 700f, listOf(0f, 1f / 6f, 2f / 6f)))
        // Never more than 4% of a short rail.
        assertEquals(0.04f, seekMagnetFraction(14f, 100f, emptyList()))
    }

    @Test
    fun theMagnetShrinksBetweenChaptersPackedCloserThanItsReach() {
        // Chapters 3% apart on a 700 px rail: 21 px, so the pull drops to 7 px either side.
        val reach = seekMagnetFraction(14f, 700f, listOf(0.5f, 0.53f, 0.2f))
        assertEquals(0.01f, reach, 0.0001f)
    }
}
