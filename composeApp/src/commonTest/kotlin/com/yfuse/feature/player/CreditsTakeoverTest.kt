package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreditsTakeoverTest {
    private val duration = 1_500_000L
    private val credits = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 1_410_000L, endMs = null)

    private fun phase(
        positionMs: Long,
        credits: PlaybackSegment? = this.credits,
        hasNext: Boolean = true,
        finished: Boolean = false,
        blocked: Boolean = false,
    ) = creditsTakeoverPhase(positionMs, duration, credits, hasNext, finished, blocked)

    @Test
    fun picks_the_earliest_credits_marker_inside_the_file() {
        val intro = PlaybackSegment(PlaybackSegmentType.Intro, startMs = 60_000L, endMs = 150_000L)
        val late = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 1_450_000L, endMs = null)
        val past = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 1_600_000L, endMs = null)
        val zero = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 0L, endMs = 5_000L)
        assertEquals(credits, creditsSegment(listOf(intro, late, past, zero, credits), duration))
        assertNull(creditsSegment(listOf(intro, past, zero), duration))
        // Duration unknown yet: nothing to be outside of.
        assertEquals(past, creditsSegment(listOf(past), durationMs = 0L))
    }

    @Test
    fun the_credits_take_the_picture_and_hand_the_last_seconds_to_the_countdown() {
        assertEquals(CreditsTakeoverPhase.Off, phase(1_409_999L))
        assertEquals(CreditsTakeoverPhase.Card, phase(1_410_000L))
        assertEquals(CreditsTakeoverPhase.Card, phase(1_489_999L))
        assertEquals(CreditsTakeoverPhase.Countdown, phase(1_490_000L))
        assertEquals(CreditsTakeoverPhase.Countdown, phase(1_499_999L))
        assertEquals(CreditsTakeoverPhase.Off, phase(1_500_000L))
    }

    @Test
    fun a_scene_after_the_credits_gets_the_whole_picture_back() {
        val early = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 1_300_000L, endMs = 1_400_000L)
        assertEquals(CreditsTakeoverPhase.Card, phase(1_350_000L, credits = early))
        assertEquals(CreditsTakeoverPhase.Off, phase(1_400_000L, credits = early))
    }

    @Test
    fun short_credits_are_left_to_the_ordinary_card() {
        val short = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 1_492_000L, endMs = null)
        assertEquals(CreditsTakeoverPhase.Off, phase(1_495_000L, credits = short))
        val brief = PlaybackSegment(PlaybackSegmentType.Credits, startMs = 1_200_000L, endMs = 1_208_000L)
        assertEquals(CreditsTakeoverPhase.Off, phase(1_204_000L, credits = brief))
    }

    @Test
    fun nothing_to_offer_or_not_ours_to_take_leaves_the_picture_alone() {
        assertEquals(CreditsTakeoverPhase.Off, phase(1_420_000L, credits = null))
        assertEquals(CreditsTakeoverPhase.Off, phase(1_420_000L, hasNext = false))
        assertEquals(CreditsTakeoverPhase.Off, phase(1_420_000L, finished = true))
        assertEquals(CreditsTakeoverPhase.Off, phase(1_420_000L, blocked = true))
        assertEquals(
            CreditsTakeoverPhase.Off,
            creditsTakeoverPhase(1_420_000L, 0L, credits, hasNext = true, finished = false, blocked = false),
        )
    }
}
