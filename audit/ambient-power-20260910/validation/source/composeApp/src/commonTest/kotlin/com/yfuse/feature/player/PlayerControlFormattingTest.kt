package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerControlFormattingTest {
    @Test
    fun unknownDurationNeverRendersAsCompleted() {
        assertEquals(0f, playbackProgressFraction(positionMs = 80_000L, durationMs = 0L))
        assertEquals(0f, playbackProgressFraction(positionMs = 80_000L, durationMs = -1L))
    }

    @Test
    fun knownDurationClampsProgressToTimeline() {
        assertEquals(0.5f, playbackProgressFraction(positionMs = 50_000L, durationMs = 100_000L))
        assertEquals(1f, playbackProgressFraction(positionMs = 150_000L, durationMs = 100_000L))
        assertEquals(0f, playbackProgressFraction(positionMs = -1L, durationMs = 100_000L))
    }

    @Test
    fun stallKeepsTheLastValidTimeline() {
        val keeper = TestTimelineKeeper()
        val media = identity(index = 0, itemId = "movie")
        keeper.stabilize(media, state(positionMs = 40_000L, durationMs = 100_000L))

        val stalled =
            keeper.stabilize(
                media,
                state(positionMs = 0L, durationMs = 0L, buffering = true),
            )

        assertEquals(40_000L, stalled.positionMs)
        assertEquals(100_000L, stalled.durationMs)
        assertEquals(0.4f, playbackProgressFraction(stalled.positionMs, stalled.durationMs))
    }

    @Test
    fun sourceSwitchForTheSameQueueItemKeepsTimeline() {
        val keeper = TestTimelineKeeper()
        val media = identity(index = 0, itemId = "episode")
        keeper.stabilize(media, state(positionMs = 70_000L, durationMs = 140_000L))

        val replacingSource =
            keeper.stabilize(
                media,
                state(positionMs = 0L, durationMs = 140_000L, buffering = true),
            )

        assertEquals(70_000L, replacingSource.positionMs)
        assertEquals(140_000L, replacingSource.durationMs)
    }

    @Test
    fun engineSwitchForTheSameQueueItemKeepsTimeline() {
        val keeper = TestTimelineKeeper()
        val media = identity(index = 0, itemId = "dolby-movie")
        keeper.stabilize(media, state(positionMs = 25_000L, durationMs = 200_000L))

        val freshEngine = keeper.stabilize(media, PlaybackState(buffering = true))

        assertEquals(25_000L, freshEngine.positionMs)
        assertEquals(200_000L, freshEngine.durationMs)
    }

    @Test
    fun timeUnsetDoesNotOverwriteTheLastValidTimeline() {
        val keeper = TestTimelineKeeper()
        val media = identity(index = 0, itemId = "movie")
        keeper.stabilize(media, state(positionMs = 12_000L, durationMs = 90_000L))

        val timeUnset =
            keeper.stabilize(
                media,
                state(positionMs = 0L, durationMs = -9_223_372_036_854_775_807L),
            )

        assertEquals(12_000L, timeUnset.positionMs)
        assertEquals(90_000L, timeUnset.durationMs)
    }

    @Test
    fun genuinelyDifferentMediaClearsTheOldTimeline() {
        val keeper = TestTimelineKeeper()
        keeper.stabilize(identity(index = 0, itemId = "episode-1"), state(80_000L, 100_000L))

        val next =
            keeper.stabilize(
                identity(index = 1, itemId = "episode-2"),
                PlaybackState(buffering = true),
            )

        assertEquals(0L, next.positionMs)
        assertEquals(0L, next.durationMs)
        assertFalse(next.ended)
    }

    private fun identity(
        index: Int,
        itemId: String,
    ) = PlaybackTimelineIdentity(index, "server", itemId)

    private fun state(
        positionMs: Long,
        durationMs: Long,
        buffering: Boolean = false,
    ) = PlaybackState(
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = positionMs,
        buffering = buffering,
    )

    private class TestTimelineKeeper {
        private var memory = PlaybackTimelineMemory()

        fun stabilize(
            media: PlaybackTimelineIdentity?,
            reported: PlaybackState,
        ): PlaybackState {
            val resolution = stabilizePlaybackTimeline(memory, media, reported)
            memory = resolution.memory
            return resolution.state
        }
    }
}
