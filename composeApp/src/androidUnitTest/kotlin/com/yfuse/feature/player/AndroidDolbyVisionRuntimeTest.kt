package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackResourcePressure
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDolbyVisionRuntimeTest {
    @Test
    fun media_performance_class_device_allows_full_fel() {
        assertTrue(
            supportsFullDolbyFelProcessing(
                facts(mediaPerformanceClass = 31, totalMemoryBytes = 4L * GIB),
            ),
        )
    }

    @Test
    fun legacy_high_memory_gles3_device_allows_full_fel() {
        assertTrue(
            supportsFullDolbyFelProcessing(
                facts(mediaPerformanceClass = 0, totalMemoryBytes = 8L * GIB),
            ),
        )
    }

    @Test
    fun low_memory_or_runtime_pressure_forces_base_layer_budget() {
        assertFalse(
            supportsFullDolbyFelProcessing(
                facts(mediaPerformanceClass = 0, totalMemoryBytes = 4L * GIB),
            ),
        )
        assertFalse(
            supportsFullDolbyFelProcessing(
                facts(
                    mediaPerformanceClass = 31,
                    totalMemoryBytes = 8L * GIB,
                    pressure = PlaybackResourcePressure.Thermal,
                ),
            ),
        )
    }

    @Test
    fun base_layer_route_strips_both_rpu_and_p7_enhancement_layer() {
        kotlin.test.assertEquals(
            "format=dolbyvision=no:enhancement-layer=no",
            mpvDolbyVisionVideoFilter(stripToBaseLayer = true),
        )
        kotlin.test.assertEquals("", mpvDolbyVisionVideoFilter(stripToBaseLayer = false))
    }

    private fun facts(
        mediaPerformanceClass: Int,
        totalMemoryBytes: Long,
        pressure: PlaybackResourcePressure = PlaybackResourcePressure.Normal,
    ) = DolbyFelPerformanceFacts(
        sdkInt = 35,
        mediaPerformanceClass = mediaPerformanceClass,
        lowRamDevice = false,
        totalMemoryBytes = totalMemoryBytes,
        requiredGlEsVersion = 0x0003_0000,
        resourcePressure = pressure,
    )

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
