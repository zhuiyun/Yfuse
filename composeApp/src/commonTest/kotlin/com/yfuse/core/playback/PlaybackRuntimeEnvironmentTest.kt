package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRuntimeEnvironmentTest {
    @Test
    fun thermal_pressure_preserves_every_requested_mode_without_a_recovery_delay() {
        assertFalse(PlaybackResourcePressure.Thermal.requiresPowerSaving)
        for (mode in PlaybackOptimizationMode.entries) {
            val recovery = PlaybackOptimizationRecovery()
            val pressures =
                listOf(
                    PlaybackResourcePressure.Normal,
                    PlaybackResourcePressure.Thermal,
                    PlaybackResourcePressure.Normal,
                )
            pressures.forEachIndexed { index, pressure ->
                val resolved = resolvePlaybackOptimization(mode, PlaybackRuntimeEnvironment(pressure))
                assertEquals(mode, resolved.mode)
                assertNull(resolved.reason)
                assertEquals(resolved, recovery.update(resolved, index * 1_000L))
                assertEquals(0L, recovery.remainingMs(index * 1_000L))
            }
        }
    }

    @Test
    fun battery_and_system_power_saving_still_reduce_cost_without_changing_compatibility() {
        for (pressure in listOf(PlaybackResourcePressure.BatteryLow, PlaybackResourcePressure.SystemPowerSaver)) {
            assertTrue(pressure.requiresPowerSaving)
            for (mode in PlaybackOptimizationMode.entries) {
                val resolved = resolvePlaybackOptimization(mode, PlaybackRuntimeEnvironment(pressure))
                if (mode == PlaybackOptimizationMode.Compatibility || mode == PlaybackOptimizationMode.PowerSaver) {
                    assertEquals(mode, resolved.mode)
                    assertNull(resolved.reason)
                } else {
                    assertEquals(PlaybackOptimizationMode.PowerSaver, resolved.mode)
                    assertNotNull(resolved.reason)
                }
            }
        }
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
