package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscChapter
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkProgressBarTest {
    @Test
    fun progressMarkersIncludeRealDiscChapterPositions() {
        val markers =
            playbackProgressMarkers(
                skip = SkipSegmentState(),
                durationMs = 100_000L,
                chapters =
                    listOf(
                        PlaybackDiscChapter(index = 0, title = "序章", startMs = 0L),
                        PlaybackDiscChapter(index = 1, title = "第二章", startMs = 42_000L),
                    ),
            )

        assertEquals(listOf(0L, 42_000L), markers.map(PlaybackProgressMarker::positionMs))
        assertEquals(listOf("序章", "第二章"), markers.map(PlaybackProgressMarker::label))
    }
}
