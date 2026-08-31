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
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.deviceMatrix)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.continuousSoak)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.queueSoak)
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
    fun absence_of_a_required_native_component_fails_dependency_purity() {
        val report =
            evaluateYCoreNativeBaseline(
                completeEvidence().copy(
                    runtimeDependencies =
                        completeEvidence().runtimeDependencies - YCoreNativeRuntimeDependency.Libass,
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

    @Test
    fun code_coverage_cannot_replace_physical_device_and_soak_evidence() {
        val evidence =
            completeEvidence().copy(
                physicalDevices = 0,
                chipsetFamilies = 0,
                continuousSoakMinutes = 0,
                queueSoakMinutes = 0,
            )

        val report = evaluateYCoreNativeBaseline(evidence)

        assertFalse(report.releaseReady)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.deviceMatrix)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.continuousSoak)
        assertEquals(YCoreNativeGateStatus.NotMeasured, report.queueSoak)
    }

    @Test
    fun transport_stream_and_vc1_are_admitted_to_runtime_capability_checks() {
        val block =
            evaluateCore2NativeBaseline(
                Core2NativeBaselineSource(
                    hasMetadata = true,
                    scheme = "https",
                    container = "m2ts",
                    videoCodec = "vc1",
                    serverTranscode = false,
                    adaptiveManifest = false,
                    disc = false,
                    drm = false,
                    dolbyVision = false,
                    externalSubtitleSupported = true,
                ),
            )

        assertEquals(null, block)
    }

    private fun completeEvidence(
        dependencies: Set<YCoreNativeRuntimeDependency> =
            setOf(
                YCoreNativeRuntimeDependency.AndroidPlatform,
                YCoreNativeRuntimeDependency.YCoreNative,
                YCoreNativeRuntimeDependency.YCoreGpu,
                YCoreNativeRuntimeDependency.Ffmpeg,
                YCoreNativeRuntimeDependency.Libass,
                YCoreNativeRuntimeDependency.Libbluray,
            ),
    ) = YCoreNativeBaselineEvidence(
        runtimeDependencies = dependencies,
        passedCapabilities = YCoreNativeBaselineCapability.entries.toSet(),
        mediaCases = 18,
        seekCycles = 1_000,
        surfaceRecreations = 1_000,
        queueTransitions = 100,
        physicalDevices = 4,
        chipsetFamilies = 3,
        continuousSoakMinutes = 8 * 60,
        queueSoakMinutes = 24 * 60,
    )
}
