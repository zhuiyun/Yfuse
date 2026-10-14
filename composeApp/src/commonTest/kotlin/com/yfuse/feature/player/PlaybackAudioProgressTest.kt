package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackAudioProgressTest {
    @Test
    fun configuration_and_a_single_nonzero_timestamp_do_not_prove_output() {
        val progress = PlaybackAudioProgress()
        assertFalse(progress.observe(3_000_000L, 100L, true))
        assertFalse(progress.observe(3_000_000L, 600L, true))
        assertTrue(progress.observe(3_500_000L, 1_100L, true))
        assertFalse(progress.observe(3_500_000L, 2_601L, true))
    }

    @Test
    fun seek_and_resume_require_new_progress() {
        val progress = PlaybackAudioProgress()
        progress.observe(0L, 0L, true)
        assertTrue(progress.observe(500_000L, 500L, true))
        assertFalse(progress.observe(60_000_000L, 600L, true))
        assertTrue(progress.observe(60_500_000L, 1_100L, true))
        assertFalse(progress.observe(60_500_000L, 1_200L, false))
        assertFalse(progress.observe(60_500_000L, 2_000L, true))
        assertTrue(progress.observe(61_000_000L, 2_500L, true))
        progress.reset()
        assertFalse(progress.observe(61_500_000L, 3_000L, true))
    }

    @Test
    fun missing_and_regressing_timestamps_invalidate_previous_evidence() {
        val progress = PlaybackAudioProgress()
        progress.observe(500_000L, 0L, true)
        assertTrue(progress.observe(1_000_000L, 500L, true))
        assertFalse(progress.observe(null, 600L, true))
        assertFalse(progress.observe(1_500_000L, 1_000L, true))
        assertFalse(progress.observe(0L, 1_100L, true))
        assertFalse(progress.observe(-1L, 1_200L, true))
    }
}
