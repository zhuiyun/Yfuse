package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackOptimizationRecoveryTest {
    private val quality = ResolvedPlaybackOptimization(PlaybackOptimizationMode.Quality)
    private val systemPowerSaver = ResolvedPlaybackOptimization(PlaybackOptimizationMode.PowerSaver, "系统省电")

    @Test
    fun repeated_power_saving_flaps_do_not_repeatedly_restore_expensive_playback() {
        val recovery = PlaybackOptimizationRecovery()
        assertEquals(quality, recovery.update(quality, 0L))
        assertEquals(systemPowerSaver, recovery.update(systemPowerSaver, 1_000L))
        assertEquals(PlaybackOptimizationMode.PowerSaver, recovery.update(quality, 2_000L).mode)
        assertEquals(30_000L, recovery.remainingMs(2_000L))
        assertEquals(systemPowerSaver, recovery.update(systemPowerSaver, 20_000L))
        assertEquals(PlaybackOptimizationMode.PowerSaver, recovery.update(quality, 21_000L).mode)
        assertEquals(PlaybackOptimizationMode.PowerSaver, recovery.update(quality, 50_999L).mode)
        assertEquals(quality, recovery.update(quality, 51_000L))
        assertEquals(0L, recovery.remainingMs(51_000L))
    }

    @Test
    fun explicit_power_saver_is_not_treated_as_a_safety_recovery() {
        val recovery = PlaybackOptimizationRecovery()
        recovery.update(ResolvedPlaybackOptimization(PlaybackOptimizationMode.PowerSaver), 0L)
        assertEquals(quality, recovery.update(quality, 1L))
    }
}
