package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YSubtitleCueBufferTest {
    @Test
    fun prefetchedPgsDisplaysReplaceAllRectanglesAndClearAtTheirPresentationTime() {
        val buffer = YSubtitleCueBuffer()
        buffer.apply(display(1_000_000L, "A-left", "A-right"))
        buffer.apply(YSubtitleDecodeResult.NoOutput)
        buffer.apply(display(3_000_000L, "B"))
        buffer.apply(display(5_000_000L))

        assertEquals(listOf("A-left", "A-right"), buffer.activeIds(1_500_000L))
        assertEquals(listOf("B"), buffer.activeIds(3_500_000L))
        assertEquals(emptyList(), buffer.activeIds(5_500_000L))
        assertEquals(emptyList(), buffer.activeIds(3_600_000_000L))
        assertEquals(2L, buffer.replacementCount)
        assertEquals(1L, buffer.clearCount)
    }

    @Test
    fun clearBoundariesSurviveReorderedOutputAndEqualPtsReplacesTheWholeDisplay() {
        val buffer = YSubtitleCueBuffer()
        buffer.apply(display(5_000_000L))
        buffer.apply(display(3_000_000L, "B"))
        buffer.apply(display(1_000_000L, "old"))
        buffer.apply(display(1_000_000L, "left", "right"))

        assertEquals(listOf("left", "right"), buffer.activeIds(1_500_000L))
        assertEquals(listOf("B"), buffer.activeIds(3_500_000L))
        assertTrue(buffer.activeIds(5_500_000L).isEmpty())
    }

    @Test
    fun clearingOneTrackDoesNotAffectTheOtherAndSeekDropsPreviousGeneration() {
        val primary = YSubtitleCueBuffer()
        val secondary = YSubtitleCueBuffer()
        primary.apply(display(1_000_000L, "Chinese"))
        secondary.apply(display(1_000_000L, "English"))
        primary.apply(display(3_000_000L))
        assertTrue(primary.activeIds(3_500_000L).isEmpty())
        assertEquals(listOf("English"), secondary.activeIds(3_500_000L))

        repeat(3) {
            secondary.clear()
            assertTrue(secondary.toList().isEmpty())
            secondary.apply(display(500_000L, "after-seek-$it"))
            assertEquals(listOf("after-seek-$it"), secondary.activeIds(1_000_000L))
        }
    }

    @Test
    fun finiteBitmapExpiryAndAuthoredOverlappingTextArePreserved() {
        val buffer = YSubtitleCueBuffer()
        buffer.apply(YSubtitleDecodeResult.DisplaySet(1_000_000L, listOf(cue("finite", 1_000_000L, 2_000_000L))))
        buffer.apply(YSubtitleDecodeResult.Append(listOf(cue("text1", 1_000_000L, 4_000_000L))))
        buffer.apply(YSubtitleDecodeResult.Append(listOf(cue("text2", 2_000_000L, 4_000_000L))))
        assertEquals(listOf("text1", "text2"), buffer.activeIds(2_500_000L))
        buffer.prune(3_000_000L)
        assertEquals(2, buffer.retainedCueCount)
        buffer.prune(5_000_000L)
        assertTrue(buffer.toList().isEmpty())
    }

    @Test
    fun longPgsPlaybackRetainsOnlyTheHistoryWindowAndLatestOpenDisplay() {
        val buffer = YSubtitleCueBuffer()
        repeat(10_000) { index ->
            val positionUs = index * 2_000_000L
            buffer.apply(display(positionUs, "cue-$index"))
            buffer.prune(positionUs - 60_000_000L)
            assertTrue(buffer.retainedCueCount <= 32)
        }
        buffer.prune(50_000_000_000L)
        assertEquals(1, buffer.retainedCueCount)
        assertEquals(listOf("cue-9999"), buffer.activeIds(50_000_000_000L))
        buffer.apply(display(50_000_000_001L))
        buffer.prune(50_000_000_002L)
        assertEquals(0, buffer.retainedCueCount)
    }

    @Test
    fun futureBitmapPressurePreservesTheCurrentAndNearestPictureWithoutBlockingAvProgress() {
        val buffer = YSubtitleCueBuffer(maximumBitmapBytes = 16L)

        fun bitmap(
            startUs: Long,
            id: String,
        ) = YSubtitleDecodeResult.DisplaySet(
            startUs,
            listOf(
                YSubtitleCue(
                    id,
                    startUs,
                    Long.MAX_VALUE,
                    YSubtitlePayload.BitmapArgb(
                        2,
                        1,
                        0,
                        0,
                        2,
                        1,
                        intArrayOf(-1, -1),
                    ),
                ),
            ),
        )
        buffer.apply(bitmap(1_000_000L, "A"))
        buffer.apply(bitmap(3_000_000L, "B"))
        buffer.apply(bitmap(5_000_000L, "C"))
        buffer.prune(0L, 1_500_000L)
        assertEquals(16L, buffer.retainedBitmapBytes)
        assertEquals(1L, buffer.droppedFutureDisplayCount)
        assertEquals(listOf("A"), buffer.activeIds(1_500_000L))
        assertEquals(listOf("B"), buffer.activeIds(3_500_000L))
        assertTrue(buffer.activeIds(5_500_000L).isEmpty())
        buffer.prune(0L, 3_000_000L)
        assertEquals(8L, buffer.retainedBitmapBytes)
        buffer.prune(0L, 5_000_000L)
        assertTrue(buffer.activeIds(5_500_000L).isEmpty())
    }

    private fun display(
        startUs: Long,
        vararg ids: String,
    ) = YSubtitleDecodeResult.DisplaySet(startUs, ids.map { cue(it, startUs) })

    private fun cue(
        id: String,
        startUs: Long,
        endUs: Long = Long.MAX_VALUE,
    ) = YSubtitleCue(id, startUs, endUs, YSubtitlePayload.Text(id))

    private fun YSubtitleCueBuffer.activeIds(positionUs: Long) =
        YSubtitleTimeline(toList()).activeAt(positionUs).map { it.id }
}
