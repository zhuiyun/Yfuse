package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextItemOutputHandoffTest {
    @Test fun seeking_discards_old_pcm_tail_before_counting_newly_submitted_samples() {
        val tail = PcmTailTracker()
        tail.record(192_000)
        assertTrue(tail.pending(4, 48_000, 900_000))
        tail.reset()
        assertFalse(tail.hasSamples)
        assertFalse(tail.pending(4, 48_000, 0))
        tail.record(96_000)
        assertTrue(tail.pending(4, 48_000, 400_000))
        assertFalse(tail.pending(4, 48_000, 500_000))
    }

    @Test fun pcm_end_waits_for_submitted_frames_and_tolerates_clock_rounding() {
        assertTrue(pcmTailPending(192_000, 4, 48_000, 900_000))
        assertFalse(pcmTailPending(192_000, 4, 48_000, 1_000_000))
        assertFalse(pcmTailPending(192_000, 4, 48_000, 999_999))
        assertFalse(pcmTailPending(0, 4, 48_000, 0))
        assertFalse(pcmTailPending(192_000, 0, 48_000, 0))
    }
}
