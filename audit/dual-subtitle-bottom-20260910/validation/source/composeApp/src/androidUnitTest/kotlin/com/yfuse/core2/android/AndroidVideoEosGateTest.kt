package com.yfuse.core2.android

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidVideoEosGateTest {
    @Test
    fun no_video_input_or_empty_track_does_not_delay_end_of_stream() {
        val gate = AndroidVideoEosGate()
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 0L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 500_000_000L))
    }

    @Test
    fun long_video_with_real_first_frame_can_queue_end_of_stream_immediately() {
        val gate = AndroidVideoEosGate()
        repeat(100) { gate.inputQueued() }
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = true, nowNs = 0L))
    }

    @Test
    fun first_generation_short_clip_holds_eos_until_real_render_evidence_arrives() {
        val gate = AndroidVideoEosGate()
        gate.inputQueued()
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 0L))
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 80_000_000L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = true, nowNs = 80_000_001L))
    }

    @Test
    fun decoder_that_needs_eos_to_output_a_frame_is_unblocked_at_one_second() {
        val gate = AndroidVideoEosGate()
        gate.inputQueued()
        val firstEofNs = 7_000_000_000L
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs))
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + 999_999_999L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + 1_000_000_000L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + 1_500_000_000L))
    }

    @Test
    fun repeated_polling_does_not_extend_the_original_deadline() {
        val gate = AndroidVideoEosGate()
        gate.inputQueued()
        val firstEofNs = 20_000_000L
        repeat(1_000) { poll ->
            assertFalse(
                gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + poll * 1_000_000L),
            )
        }
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + 1_000_000_000L))
    }

    @Test
    fun reset_for_flush_or_seek_clears_queued_input_and_the_previous_deadline() {
        val gate = AndroidVideoEosGate()
        gate.inputQueued()
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 0L))
        gate.reset()
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 2_000_000_000L))
        gate.inputQueued()
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 2_000_000_000L))
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 2_999_999_999L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 3_000_000_000L))
    }

    @Test
    fun an_old_real_first_frame_does_not_bypass_a_new_seek_generation() {
        val gate = AndroidVideoEosGate()
        gate.inputQueued()
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = true, nowNs = 1L))
        gate.reset()
        gate.inputQueued()
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = 2L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = true, nowNs = 3L))
    }

    @Test
    fun time_spent_loading_input_does_not_consume_the_wait_after_eof() {
        val gate = AndroidVideoEosGate()
        gate.inputQueued()
        // The first EOF arrives much later than startup; only this call starts the bounded wait.
        val firstEofNs = 3_600_000_000_000L
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs))
        assertFalse(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + 500_000_000L))
        assertTrue(gate.mayQueueEndOfStream(firstFrameRendered = false, nowNs = firstEofNs + 1_000_000_000L))
    }
}
