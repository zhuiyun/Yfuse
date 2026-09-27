package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.model.TrickplayTimelineFrame
import com.yfuse.core.network.EmbyStream
import com.yfuse.feature.player.TrickplayStoryboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeScrubTest {
    private val sheets =
        TrickplayInfo(
            width = 320,
            height = 180,
            tileColumns = 10,
            tileRows = 10,
            intervalMs = 10_000L,
            thumbnailCount = 270,
        )

    private fun episode(id: String) =
        Episode(
            id = id,
            name = "Episode $id",
            indexNumber = 1,
            seasonNumber = 1,
            seasonId = "s1",
            overview = null,
            runtimeMinutes = 45,
            primaryTag = null,
            playedPercentage = null,
            resumePositionTicks = null,
        )

    private fun board(info: TrickplayInfo = sheets): TrickplayStoryboard =
        episodeStoryboard(info, "http://server/jellyfin", "token", "item", "source")

    @Test
    fun jellyfinsTileSheetsAreSteppedThroughFrameByFrame() {
        val board = board()
        assertEquals(
            EmbyStream.trickplayTilePattern("http://server/jellyfin", "item", "source", 320, "token"),
            board.urlPattern,
        )
        assertEquals(270, storyboardFrameCount(board))
        assertEquals(0L, storyboardFramePosition(board, 0))
        assertEquals(1_350_000L, storyboardFramePosition(board, 135))
        assertEquals(0L, storyboardFramePosition(board, -3))
        // Frame 123 is the 24th cell of the second sheet.
        val frame = board.frameAt(storyboardFramePosition(board, 123))
        assertEquals(3, frame.column)
        assertEquals(2, frame.row)
        assertEquals(board.urlPattern.replace("{index}", "1"), frame.url)
    }

    @Test
    fun embysListedFramesKeepTheirOwnTimes() {
        val frames = listOf(0L, 12_000L, 21_000L, 33_000L).map { TrickplayTimelineFrame(it, "http://server/f$it.jpg") }
        val board = board(sheets.copy(tileColumns = 1, tileRows = 1, thumbnailCount = 0, frames = frames))
        assertEquals("http://server/f0.jpg", board.urlPattern)
        assertEquals(4, storyboardFrameCount(board))
        assertEquals(21_000L, storyboardFramePosition(board, 2))
        assertEquals(33_000L, storyboardFramePosition(board, 9))
        assertEquals("http://server/f21000.jpg", board.frameAt(storyboardFramePosition(board, 2)).url)
    }

    @Test
    fun aStoryboardThatCouldNotDrawAFrameHasNone() {
        assertEquals(0, storyboardFrameCount(board(sheets.copy(width = 0))))
        assertEquals(0, storyboardFrameCount(board(sheets.copy(intervalMs = 0L))))
        assertEquals(0, storyboardFrameCount(board(sheets.copy(tileColumns = 0))))
        assertEquals(0, storyboardFrameCount(board(sheets.copy(thumbnailCount = 0))))
    }

    @Test
    fun aFrameCoversTheCardWithoutBeingStretched() {
        // A 16:9 frame on the slightly taller card: the heights match, the sides are cut.
        assertEquals(968 to 545, frameCover(880, 545, 320, 180))
        // A 4:3 frame on a wider box: the widths match, top and bottom are cut.
        assertEquals(1000 to 750, frameCover(1000, 400, 320, 240))
        assertEquals(880 to 2180, frameCover(880, 545, 320, Int.MAX_VALUE))
        assertEquals(880 to 545, frameCover(880, 545, 0, 180))
    }

    @Test
    fun aHeldEpisodeIsFetchedOnceAndOnlyOnceItsCardLifts() {
        val calls = mutableListOf<String>()
        val trickplay =
            EpisodeTrickplay(CoroutineScope(Dispatchers.Unconfined)) { serverId, episode ->
                calls += "$serverId/${episode.id}"
                Result.success(board())
            }
        val scrub = trickplay.scrub("s1", episode("e1"))
        assertEquals(emptyList(), calls)
        assertEquals(0, scrub.frameCount)
        scrub.prepare()
        assertEquals(270, scrub.frameCount)
        assertEquals("00:10", scrub.label(1))
        scrub.prepare()
        trickplay.scrub("s1", episode("e1")).prepare()
        assertEquals(listOf("s1/e1"), calls)
        assertEquals(270, trickplay.scrub("s1", episode("e1")).frameCount)
        assertEquals(0, trickplay.scrub("s2", episode("e1")).frameCount)
    }

    @Test
    fun aServerWithoutFramesIsAskedOnceButAFailureIsAskedAgain() {
        val calls = mutableListOf<String>()
        val trickplay =
            EpisodeTrickplay(CoroutineScope(Dispatchers.Unconfined)) { _, episode ->
                calls += episode.id
                if (episode.id == "none") Result.success(null) else Result.failure(IllegalStateException("offline"))
            }
        val none = trickplay.scrub("s1", episode("none"))
        none.prepare()
        none.prepare()
        assertEquals(0, none.frameCount)
        assertEquals("", none.label(0))
        val failing = trickplay.scrub("s1", episode("down"))
        failing.prepare()
        failing.prepare()
        assertEquals(0, failing.frameCount)
        assertEquals(listOf("none", "down", "down"), calls)
    }
}
