package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenPlayerOutputPolicyTest {
    @Test
    fun software_surface_frames_work_when_draw_counter_is_not_reported() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(7, draws = 0, surfaceFrames = 40)
        assertFalse(gate.observe(7, 0, 1920, 1080, true, 40))
        assertTrue(gate.observe(7, 0, 1920, 1080, true, 41))
        assertTrue(gate.observe(7, 0, 1920, 1080, true, 41))
    }

    @Test
    fun embedded_decoder_draws_do_not_require_optional_surface_frame_counts() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(7, draws = 500)
        assertFalse(gate.observe(7, 500, 320, 180, true))
        assertTrue(gate.observe(7, 501, 320, 180, true))
    }

    @Test
    fun source_surface_and_dimensions_are_required_even_with_pixel_evidence() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(8)
        assertFalse(gate.observe(7, 500, 320, 180, true, 500, true))
        assertFalse(gate.observe(8, 500, 320, 180, false, 500, true))
        assertFalse(gate.observe(8, 500, 0, 0, true, 500, true))
        assertTrue(gate.observe(8, 0, 320, 180, true, 0, true))
    }

    @Test
    fun seek_and_surface_reset_invalidate_previous_picture() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(8)
        assertTrue(gate.observe(8, 1, 320, 180, true))
        gate.reset(8, 1, 0)
        assertFalse(gate.observe(8, 1, 320, 180, true))
        assertTrue(gate.observe(8, 2, 320, 180, true))
    }

    @Test
    fun native_counter_reset_accepts_new_frames_without_waiting_for_old_total() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(9, 90_000, 90_000)
        assertTrue(gate.observe(9, 0, 320, 180, true, 1))
        assertFalse(gate.observe(9, 0, 320, 180, true, 0))
        assertTrue(gate.observe(9, 0, 320, 180, true, 1))
    }

    @Test
    fun pixel_copy_success_alone_or_flat_buffers_do_not_confirm_video() {
        val pixels = SenPlayerPixelEvidence()
        val token = SenPlayerOutputToken(7, 1, 2)
        assertFalse(pixels.observe(token, null, 0))
        assertFalse(pixels.observe(token, 1, 0))
        assertFalse(pixels.observe(token, 2, 0))
        assertFalse(pixels.observe(token, 3, 100))
        assertFalse(pixels.observe(token, 3, 100))
        assertTrue(pixels.observe(token, 4, 100))
    }

    @Test
    fun a_source_seek_or_surface_change_requires_fresh_pixel_samples() {
        val pixels = SenPlayerPixelEvidence()
        val tokens =
            listOf(
                SenPlayerOutputToken(7, 1, 2),
                SenPlayerOutputToken(8, 1, 2),
                SenPlayerOutputToken(8, 2, 2),
                SenPlayerOutputToken(8, 2, 3),
            )
        for (token in tokens) {
            assertFalse(pixels.observe(token, 10, 100))
            assertTrue(pixels.observe(token, 11, 100))
        }
    }

    @Test
    fun zero_frames_and_no_progress_never_trigger_a_surface_rebind() {
        val watchdog = SenPlayerOutputWatchdog()
        repeat(120) { second ->
            assertEquals(SenPlayerOutputAction.None, watchdog.observe(second * 1_000L, true, false, 0, 0))
        }
    }

    @Test
    fun buffering_paused_or_missing_surface_does_not_earn_recovery_time() {
        val watchdog = SenPlayerOutputWatchdog()
        repeat(120) { second ->
            assertEquals(
                SenPlayerOutputAction.None,
                watchdog.observe(
                    second * 1_000L,
                    false,
                    false,
                    second * 1_000L,
                    second * 24L,
                ),
            )
        }
        assertEquals(SenPlayerOutputAction.None, watchdog.observe(120_000, true, false, 120_000, 3_000))
    }

    @Test
    fun decoded_progress_without_picture_rebinds_only_once_then_warns() {
        val watchdog = SenPlayerOutputWatchdog()
        val actions =
            (0..60).map { second ->
                watchdog.observe(second * 1_000L, true, false, second * 1_000L, second * 24L)
            }
        assertEquals(1, actions.count { it == SenPlayerOutputAction.Rebind })
        assertEquals(SenPlayerOutputAction.Rebind, actions[8])
        assertEquals(1, actions.count { it == SenPlayerOutputAction.Warn })
        assertTrue(watchdog.warning)
    }

    @Test
    fun clock_without_decode_evidence_warns_without_rebinding() {
        val watchdog = SenPlayerOutputWatchdog()
        val actions = (0..30).map { second -> watchdog.observe(second * 1_000L, true, false, second * 1_000L, 0) }
        assertFalse(actions.contains(SenPlayerOutputAction.Rebind))
        assertEquals(1, actions.count { it == SenPlayerOutputAction.Warn })
    }

    @Test
    fun a_long_gap_or_background_resume_is_not_counted_as_active_decoding() {
        val watchdog = SenPlayerOutputWatchdog()
        watchdog.observe(0, true, false, 0, 0)
        assertEquals(SenPlayerOutputAction.None, watchdog.observe(120_000, true, false, 120_000, 500))
    }

    @Test
    fun confirmed_picture_clears_warning_and_prevents_recovery() {
        val watchdog = SenPlayerOutputWatchdog()
        repeat(20) { second -> watchdog.observe(second * 1_000L, true, false, second * 1_000L, 0) }
        assertTrue(watchdog.warning)
        repeat(30) { second ->
            assertEquals(
                SenPlayerOutputAction.None,
                watchdog.observe(
                    (20 + second) * 1_000L,
                    true,
                    true,
                    20_000L + second * 1_000,
                    second * 24L,
                ),
            )
        }
        assertFalse(watchdog.warning)
    }

    @Test
    fun startup_is_excluded_and_each_starvation_interval_is_counted_once() {
        val tracker = SenPlayerRebufferTracker()
        tracker.observe(0, "loading", true)
        tracker.observe(8_000, "buffering", true)
        assertEquals(0, tracker.events)
        tracker.observe(10_000, "playing", true)
        tracker.observe(11_000, "buffering", true)
        tracker.observe(12_000, "buffering", true)
        assertEquals(1, tracker.events)
        assertEquals(1_000, tracker.durationMs)
        tracker.observe(15_000, "playing", true)
        tracker.observe(17_000, "buffering", true)
        tracker.observe(19_000, "paused", false)
        assertEquals(2, tracker.events)
        assertEquals(6_000, tracker.durationMs)
        assertEquals(4_000, tracker.longestMs)
    }

    @Test
    fun explicit_seek_buffering_is_excluded_even_if_native_skips_the_seeking_phase() {
        val tracker = SenPlayerRebufferTracker()
        tracker.observe(0, "playing", true)
        tracker.seeking(1_000)
        tracker.observe(2_000, "buffering", true)
        tracker.observe(20_000, "playing", true)
        assertEquals(0, tracker.events)
        assertEquals(0, tracker.durationMs)
        tracker.observe(21_000, "buffering", true)
        tracker.observe(23_000, "playing", true)
        assertEquals(1, tracker.events)
        assertEquals(2_000, tracker.durationMs)
    }

    @Test
    fun pause_and_failure_close_the_buffer_interval() {
        val tracker = SenPlayerRebufferTracker()
        tracker.observe(0, "playing", true)
        tracker.observe(1_000, "buffering", true)
        tracker.observe(2_000, "buffering", false)
        tracker.observe(10_000, "failed", false)
        assertEquals(1, tracker.events)
        assertEquals(1_000, tracker.durationMs)
    }
}
