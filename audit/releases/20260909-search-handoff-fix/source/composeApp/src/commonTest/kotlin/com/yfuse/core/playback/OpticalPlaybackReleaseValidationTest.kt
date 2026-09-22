package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpticalPlaybackReleaseValidationTest {
    @Test
    fun missing_physical_evidence_is_not_a_release_pass() {
        val report = evaluateOpticalPlaybackRelease(OpticalPlaybackValidationInput())

        assertFalse(report.releaseReady)
        assertTrue(report.failed.isEmpty())
        assertTrue("nativeAar" in report.notMeasured)
        assertTrue("remoteIso" in report.notMeasured)
        assertTrue("multiAngle" in report.notMeasured)
        assertTrue("largeProRes" in report.notMeasured)
    }

    @Test
    fun one_explicit_failure_keeps_the_candidate_blocked_even_when_other_samples_pass() {
        val report =
            evaluateOpticalPlaybackRelease(
                allPassingInput().copy(remoteIsoPlaybackVerified = false),
            )

        assertFalse(report.releaseReady)
        assertEquals(listOf("remoteIso"), report.failed)
        assertTrue(report.notMeasured.isEmpty())
    }

    @Test
    fun release_ready_requires_every_optical_and_regression_gate_to_pass() {
        val report = evaluateOpticalPlaybackRelease(allPassingInput())

        assertTrue(report.releaseReady)
        assertTrue(report.failed.isEmpty())
        assertTrue(report.notMeasured.isEmpty())
        assertTrue(report.checks.values.all { it.gate == OpticalValidationGate.Pass })
    }

    private fun allPassingInput() =
        OpticalPlaybackValidationInput(
            nativeAarBuiltAndVerified = true,
            arm64PageSize16kVerified = true,
            localIsoMainFeatureVerified = true,
            localBdmvFilesystemVerified = true,
            localBdmvSafVerified = true,
            remoteIsoRangePreflightVerified = true,
            remoteIsoPlaybackVerified = true,
            remoteIsoFallbackVerified = true,
            titleChapterSeekResumeVerified = true,
            hdmvRootAndPopupMenuVerified = true,
            hdmvTouchAndDpadVerified = true,
            multiAngleVerified = true,
            hdr10Verified = true,
            hdr10PlusVerified = true,
            hlgVerified = true,
            dolbyVisionVerified = true,
            dolbyVisionP5NativeVerified = true,
            dolbyVisionP8NativeVerified = true,
            dolbyVisionP7BaseLayerVerified = true,
            dolbyVisionP7RpuVerified = true,
            dolbyVisionP7FelVerified = true,
            trueHdAtmosVerified = true,
            dtsHdVerified = true,
            pgsVerified = true,
            largeIso100GiBVerified = true,
            proRes100GiBVerified = true,
            ordinaryMediaRegressionVerified = true,
            soakAndThermalVerified = true,
        )
}
