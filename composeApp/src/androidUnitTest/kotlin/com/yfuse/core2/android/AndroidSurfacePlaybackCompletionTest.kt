package com.yfuse.core2.android

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSurfacePlaybackCompletionTest {
    @Test
    fun a_passed_release_deadline_never_substitutes_for_real_decoder_eos() {
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(100L)
        assertFalse(completion.ended(false, true, false, 1_000L))
        assertTrue(completion.ended(true, true, false, 1_000L))
    }

    @Test
    fun decoder_eos_still_waits_for_audio_output_to_drain() {
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(100L)
        assertFalse(completion.ended(true, false, false, 200L))
        assertTrue(completion.ended(true, true, false, 200L))
    }

    @Test
    fun a_pending_paused_preview_prevents_completion_even_after_both_outputs_end() {
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(100L)
        assertFalse(completion.ended(true, true, true, 200L))
        assertTrue(completion.ended(true, true, false, 200L))
    }

    @Test
    fun output_reordering_cannot_shorten_the_latest_scheduled_surface_deadline() {
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(100L)
        completion.frameReleased(300L)
        completion.frameReleased(200L)
        assertFalse(completion.ended(true, true, false, 299L))
        assertTrue(completion.ended(true, true, false, 300L))
    }

    @Test
    fun missing_callback_does_not_block_eos_or_turn_submission_into_verified_output() {
        val evidence = AndroidVideoOutputEpoch()
        evidence.reset(nowNs = 100L)
        evidence.submitted(presentationTimeUs = 9_500_000L)
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(200L)
        assertFalse(evidence.verified)
        assertFalse(completion.ended(true, true, false, 199L))
        assertTrue(completion.ended(true, true, false, 200L))
        assertFalse(evidence.verified, "Ending a drained item is not a rendered-frame callback")
    }

    @Test
    fun an_empty_video_tail_has_no_artificial_render_deadline() {
        val completion = AndroidSurfacePlaybackCompletion()
        assertFalse(completion.ended(false, true, false, 0L))
        assertTrue(completion.ended(true, true, false, 0L))
    }

    @Test
    fun reset_discards_the_old_surface_deadline_before_a_new_seek_or_surface() {
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(10_000L)
        completion.reset()
        assertTrue(completion.ended(true, true, false, 0L))
        completion.frameReleased(100L)
        assertFalse(completion.ended(true, true, false, 99L))
        assertTrue(completion.ended(true, true, false, 100L))
    }

    @Test
    fun immediate_release_has_no_additional_callback_timeout() {
        val completion = AndroidSurfacePlaybackCompletion()
        val releaseNs = 15_000_000_000L
        completion.frameReleased(releaseNs)
        assertTrue(completion.ended(true, true, false, releaseNs))
    }

    @Test
    fun a_negative_monotonic_clock_origin_is_not_mistaken_for_an_unreleased_frame() {
        val completion = AndroidSurfacePlaybackCompletion()
        completion.frameReleased(-100L)
        assertFalse(completion.ended(true, true, false, -101L))
        assertTrue(completion.ended(true, true, false, -100L))
    }
}
