package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackSourceRequirements
import com.yfuse.core.playback.PlaybackVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackNetworkRecoveryEvidenceTest {
    @Test
    fun five_millisecond_seek_tick_is_not_recovery() {
        assertNull(state(position = 1_010_287L).networkRecoveryOutcome(1_010_282L, probe()))
    }

    @Test
    fun clock_progress_without_both_expected_sinks_is_not_recovery() {
        val playing = state(position = 1_014_000L)
        assertNull(
            playing
                .copy(diagnostics = playing.diagnostics.copy(videoReadiness = PlaybackOutputReadiness.Waiting))
                .networkRecoveryOutcome(1_010_282L, probe()),
        )
        assertNull(
            playing
                .copy(diagnostics = playing.diagnostics.copy(audioReadiness = PlaybackOutputReadiness.Released))
                .networkRecoveryOutcome(1_010_282L, probe()),
        )
    }

    @Test
    fun short_lived_output_followed_by_buffering_stays_pending() {
        assertNull(state(position = 1_011_700L).networkRecoveryOutcome(1_010_282L, probe()))
        assertNull(state(position = 1_014_000L).copy(buffering = true).networkRecoveryOutcome(1_010_282L, probe()))
        assertEquals(true, state(position = 1_014_000L).networkRecoveryOutcome(1_010_282L, probe()))
    }

    @Test
    fun a_new_output_generation_cannot_reuse_old_rendering_flags() {
        val playing = state(position = 1_014_000L)
        assertNull(
            playing
                .copy(
                    diagnostics =
                        playing.diagnostics.copy(
                            outputEvidence = PlaybackOutputEvidence(sessionRevision = 2L),
                        ),
                ).networkRecoveryOutcome(1_010_282L, probe()),
        )
    }

    @Test
    fun audio_only_and_unverifiable_backends_can_recover_but_errors_cannot() {
        val playing = state(position = 1_014_000L)
        assertEquals(
            true,
            playing
                .copy(diagnostics = playing.diagnostics.copy(videoReadiness = PlaybackOutputReadiness.Waiting))
                .networkRecoveryOutcome(1_010_282L, probe(video = false)),
        )
        assertEquals(
            true,
            playing
                .copy(
                    diagnostics =
                        PlaybackDiagnostics(
                            videoReadiness = PlaybackOutputReadiness.Unknown,
                            audioReadiness = PlaybackOutputReadiness.Unknown,
                        ),
                ).networkRecoveryOutcome(1_010_282L, probe()),
        )
        assertEquals(false, playing.copy(error = "network").networkRecoveryOutcome(1_010_282L, probe()))
    }

    private fun state(position: Long) =
        PlaybackState(
            playing = true,
            buffering = false,
            positionMs = position,
            diagnostics =
                PlaybackDiagnostics(
                    videoReadiness = PlaybackOutputReadiness.Rendering,
                    audioReadiness = PlaybackOutputReadiness.Rendering,
                ),
        )

    private fun probe(video: Boolean = true) =
        PlaybackMediaProbe(
            container = "mp4",
            discSource = false,
            source =
                PlaybackSourceRequirements(
                    false,
                    false,
                    null,
                    videoCodec = if (video) PlaybackVideoCodec.H264 else null,
                ),
            audioCodec = PlaybackAudioCodec.Aac,
            hasServerTranscode = false,
        )
}
