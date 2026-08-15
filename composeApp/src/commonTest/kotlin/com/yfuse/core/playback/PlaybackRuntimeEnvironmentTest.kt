package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaybackRuntimeEnvironmentTest {
    @Test
    fun thermal_pressure_overrides_quality_with_power_saver() {
        val resolved =
            resolvePlaybackOptimization(
                requested = PlaybackOptimizationMode.Quality,
                environment =
                    PlaybackRuntimeEnvironment(
                        pressure = PlaybackResourcePressure.Thermal,
                        batteryPercent = 63,
                    ),
            )

        assertEquals(PlaybackOptimizationMode.PowerSaver, resolved.mode)
        assertNotNull(resolved.reason)
    }

    @Test
    fun compatibility_mode_survives_low_battery_for_difficult_media() {
        val resolved =
            resolvePlaybackOptimization(
                requested = PlaybackOptimizationMode.Compatibility,
                environment =
                    PlaybackRuntimeEnvironment(
                        pressure = PlaybackResourcePressure.BatteryLow,
                        batteryPercent = 10,
                    ),
            )

        assertEquals(PlaybackOptimizationMode.Compatibility, resolved.mode)
        assertNull(resolved.reason)
    }
}

