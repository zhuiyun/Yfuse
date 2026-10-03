package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipWithoutCountdownTest {
    private val fullLength = 2_400_000L

    private fun intro(
        startMs: Long,
        endMs: Long?,
    ) = PlaybackSegment(PlaybackSegmentType.Intro, startMs, endMs)

    @Test
    fun a_short_intro_or_recap_goes_at_once() {
        assertTrue(skipsWithoutCountdown(intro(120_000L, 130_000L), positionMs = 120_500L, durationMs = fullLength))
        assertTrue(skipsWithoutCountdown(intro(120_000L, 124_000L), positionMs = 120_000L, durationMs = fullLength))
    }

    @Test
    fun an_intro_the_episode_opens_on_goes_at_once() {
        assertTrue(skipsWithoutCountdown(intro(0L, 90_000L), positionMs = 0L, durationMs = fullLength))
        assertTrue(skipsWithoutCountdown(intro(1_000L, 90_000L), positionMs = 2_500L, durationMs = fullLength))
    }

    @Test
    fun any_intro_of_a_short_episode_goes_at_once() {
        assertTrue(skipsWithoutCountdown(intro(20_000L, 50_000L), positionMs = 25_000L, durationMs = 180_000L))
    }

    @Test
    fun a_long_intro_reached_mid_file_keeps_the_countdown() {
        assertFalse(skipsWithoutCountdown(intro(120_000L, 210_000L), positionMs = 120_500L, durationMs = fullLength))
        assertFalse(skipsWithoutCountdown(intro(0L, 90_000L), positionMs = 30_000L, durationMs = fullLength))
    }

    @Test
    fun credits_and_open_ended_segments_keep_the_countdown() {
        val credits = PlaybackSegment(PlaybackSegmentType.Credits, 2_390_000L, null)
        assertFalse(skipsWithoutCountdown(credits, positionMs = 2_390_000L, durationMs = fullLength))
        assertFalse(skipsWithoutCountdown(intro(120_000L, null), positionMs = 120_000L, durationMs = fullLength))
        assertFalse(skipsWithoutCountdown(null, positionMs = 0L, durationMs = fullLength))
    }
}
