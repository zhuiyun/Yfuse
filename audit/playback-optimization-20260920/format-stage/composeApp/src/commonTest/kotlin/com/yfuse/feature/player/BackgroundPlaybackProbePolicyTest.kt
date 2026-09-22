package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundPlaybackProbePolicyTest {
    @Test
    fun optional_probe_requires_output_and_eight_seconds_at_current_speed() {
        val state = PlaybackState(playing = true, buffering = false, bufferedPositionMs = 8_000L)
        assertFalse(backgroundPlaybackProbeAllowed(state))
        val rendering =
            state.copy(
                diagnostics = PlaybackDiagnostics(videoReadiness = PlaybackOutputReadiness.Rendering),
            )
        assertTrue(backgroundPlaybackProbeAllowed(rendering))
        assertFalse(backgroundPlaybackProbeAllowed(rendering.copy(speed = 2f)))
        assertTrue(backgroundPlaybackProbeAllowed(rendering.copy(speed = 2f, bufferedPositionMs = 16_000L)))
        assertFalse(backgroundPlaybackProbeAllowed(rendering.copy(buffering = true)))
        assertFalse(backgroundPlaybackProbeAllowed(rendering.copy(playing = false)))
        assertFalse(backgroundPlaybackProbeAllowed(rendering.copy(speed = Float.NaN)))
        assertTrue(backgroundPlaybackProbeAllowed(state.copy(error = "Cannot open")))
    }
}
