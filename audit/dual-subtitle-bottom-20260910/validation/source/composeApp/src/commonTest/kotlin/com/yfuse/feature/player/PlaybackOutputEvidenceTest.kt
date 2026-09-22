package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackOutputEvidenceTest {
    @Test
    fun structured_evidence_overrides_legacy_readiness_after_session_starts() {
        val diagnostics =
            PlaybackDiagnostics(
                videoReadiness = PlaybackOutputReadiness.Rendering,
                audioReadiness = PlaybackOutputReadiness.Rendering,
                outputEvidence =
                    PlaybackOutputEvidence(
                        sessionRevision = 4L,
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                        audioReadiness = PlaybackOutputReadiness.Unknown,
                    ),
            )

        assertEquals(PlaybackOutputReadiness.Waiting, diagnostics.effectiveVideoReadiness)
        assertEquals(PlaybackOutputReadiness.Unknown, diagnostics.effectiveAudioReadiness)
    }

    @Test
    fun next_session_clears_old_output_claims_and_increments_revision() {
        val previous =
            PlaybackOutputEvidence(
                sessionRevision = 9L,
                videoReadiness = PlaybackOutputReadiness.Rendering,
                videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                outputDynamicRange = "Dolby Vision",
                audioMode = PlaybackAudioOutputMode.Passthrough,
            )

        val next = previous.nextSession()

        assertEquals(10L, next.sessionRevision)
        assertEquals(PlaybackOutputReadiness.Waiting, next.videoReadiness)
        assertEquals(PlaybackEvidenceConfidence.Unknown, next.videoConfidence)
        assertEquals("", next.outputDynamicRange)
        assertEquals(PlaybackAudioOutputMode.Unknown, next.audioMode)
    }
}
