package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerItemEndTest {
    @Test
    fun `next-up card has no countdown when automatic next episode is off`() {
        assertNull(nextUpCountdownLabel(autoAdvance = false, remainingMs = 8_000L, speed = 1f))
        assertNull(nextUpCountdownLabel(autoAdvance = false, remainingMs = 1_000L, speed = 1f))
    }

    @Test
    fun `next-up countdown stays coarse while automatic next episode is on`() {
        assertEquals("即将自动播放", nextUpCountdownLabel(autoAdvance = true, remainingMs = 8_000L, speed = 1f))
        assertEquals("马上自动播放", nextUpCountdownLabel(autoAdvance = true, remainingMs = 3_000L, speed = 1f))
        assertEquals("马上自动播放", nextUpCountdownLabel(autoAdvance = true, remainingMs = 6_000L, speed = 2f))
    }

    @Test
    fun `an ended item stops at its end whether or not another follows`() {
        assertTrue(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(ended = true)))
        assertTrue(
            playbackStoppedAtItemEnd(
                endOfFirstOfTwo().copy(currentIndex = 1, ended = true),
            ),
        )
    }

    @Test
    fun `an item parked on its last frame with another queued counts as ended`() {
        assertTrue(playbackStoppedAtItemEnd(endOfFirstOfTwo()))
        assertTrue(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(positionMs = 1_199_200L)))
    }

    @Test
    fun `an ordinary pause, a stall or a failure is not the end of the item`() {
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(positionMs = 600_000L)))
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(playing = true)))
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(buffering = true)))
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(durationMs = 0L, positionMs = 0L)))
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(error = "播放失败")))
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(ended = true, error = "播放失败")))
    }

    @Test
    fun `the last item is only over once the engine says it has ended`() {
        assertFalse(playbackStoppedAtItemEnd(endOfFirstOfTwo().copy(currentIndex = 1)))
    }

    /** ExoPlayer after pauseAtEndOfMediaItems stopped it: paused on the final frame of item 0 of 2. */
    private fun endOfFirstOfTwo() =
        PlaybackState(
            playing = false,
            buffering = false,
            positionMs = 1_200_000L,
            durationMs = 1_200_000L,
            currentIndex = 0,
            itemCount = 2,
        )
}
