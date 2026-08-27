package com.yfuse.core2.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YCoreNativeReadinessTest {
    @Test
    fun missing_evidence_never_releases_the_native_kernel() {
        val report = evaluateYCoreNativeBaseline(YCoreNativeBaselineEvidence())

        assertFalse(report.releaseReady)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.dependencyPurity)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.capabilityCoverage)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.mediaMatrix)
    }

    @Test
    fun observing_any_legacy_engine_fails_dependency_purity() {
        val report =
            evaluateYCoreNativeBaseline(
                completeEvidence(
                    dependencies =
                        setOf(
                            YCoreNativeRuntimeDependency.AndroidPlatform,
                            YCoreNativeRuntimeDependency.YCoreNative,
                            YCoreNativeRuntimeDependency.Mpv,
                        ),
                ),
            )

        assertFalse(report.releaseReady)
        assertEquals(YCoreNativeGateStatus.Fail, report.dependencyPurity)
    }

    @Test
    fun complete_pure_evidence_releases_the_baseline() {
        val report = evaluateYCoreNativeBaseline(completeEvidence())

        assertTrue(report.releaseReady)
        assertEquals(YCoreNativeGateStatus.Pass, report.dependencyPurity)
        assertEquals(YCoreNativeGateStatus.Pass, report.capabilityCoverage)
    }

    private fun completeEvidence(
        dependencies: Set<YCoreNativeRuntimeDependency> =
            setOf(
                YCoreNativeRuntimeDependency.AndroidPlatform,
                YCoreNativeRuntimeDependency.YCoreNative,
                YCoreNativeRuntimeDependency.Ffmpeg,
            ),
    ) = YCoreNativeBaselineEvidence(
        runtimeDependencies = dependencies,
        passedCapabilities = YCoreNativeBaselineCapability.entries.toSet(),
        mediaCases = 8,
        seekCycles = 1_000,
        surfaceRecreations = 1_000,
        queueTransitions = 100,
    )
}
