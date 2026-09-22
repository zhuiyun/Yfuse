package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackReleaseValidationTest {
    @Test
    fun complete_evidence_passes_and_reports_startup_percentiles() {
        val report =
            evaluatePlaybackReleaseGates(
                PlaybackReleaseValidationInput(
                    playbackSessions = 10_000,
                    crashedSessions = 1,
                    eligibleAutomaticRecoveries = 100,
                    successfulAutomaticRecoveries = 96,
                    automaticRecoveryTimeMs = listOf(500, 1_000),
                    startupTimeMs = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1_000),
                    avSyncAbsoluteMs = listOf(20, 80),
                    droppedFrameRatios = listOf(0.001, 0.009),
                    rebufferSamples = listOf(PlaybackRebufferValidationSample(0.009, 1.5)),
                    handoverSamples = listOf(PlaybackHandoverValidationSample(250, true)),
                    powerRegressionPercent = listOf(4.9),
                    thermalHeadroom = listOf(0.5, 0.8),
                    soakSamples =
                        listOf(
                            PlaybackSoakValidationSample(8.0, singleItem = false, healthy = true),
                            PlaybackSoakValidationSample(24.0, singleItem = true, healthy = true),
                        ),
                ),
            )

        assertTrue(report.releaseReady)
        assertEquals(500.0, report.startupTimeMs?.p50)
        assertEquals(1_000.0, report.startupTimeMs?.p95)
        assertEquals(1_000.0, report.automaticRecoveryTimeMs?.p95)
        assertEquals(80.0, report.avSyncAbsoluteMs?.maximum)
    }

    @Test
    fun missing_measurements_cannot_be_release_ready() {
        val report = evaluatePlaybackReleaseGates(PlaybackReleaseValidationInput())

        assertFalse(report.releaseReady)
        assertTrue(report.gates.all { it.status == PlaybackReleaseGateStatus.NotMeasured })
    }

    @Test
    fun threshold_violations_fail_the_corresponding_gates() {
        val report =
            evaluatePlaybackReleaseGates(
                PlaybackReleaseValidationInput(
                    playbackSessions = 1,
                    crashedSessions = 1,
                    eligibleAutomaticRecoveries = 1,
                    successfulAutomaticRecoveries = 0,
                    avSyncAbsoluteMs = listOf(81),
                    droppedFrameRatios = listOf(0.01),
                    rebufferSamples = listOf(PlaybackRebufferValidationSample(0.01, 1.5)),
                    handoverSamples = listOf(PlaybackHandoverValidationSample(251, true)),
                    powerRegressionPercent = listOf(5.1),
                    soakSamples = listOf(PlaybackSoakValidationSample(24.0, singleItem = true, healthy = false)),
                ),
            )

        assertFalse(report.releaseReady)
        assertTrue(report.gates.all { it.status == PlaybackReleaseGateStatus.Fail })
    }
}
