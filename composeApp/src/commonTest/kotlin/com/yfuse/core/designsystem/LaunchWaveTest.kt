package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchWaveTest {
    @AfterTest
    fun reset() = LaunchWaveGate.disarm()

    @Test
    fun an_element_rests_until_the_wave_reaches_it() {
        val before = launchWaveBob(tauMs = -1f, amplitudePx = 60f)
        assertEquals(0f, before.offset)
        assertEquals(1f, before.scale)
    }

    @Test
    fun the_first_swing_rises_then_sinks_then_settles() {
        val amplitude = 60f
        val rise = launchWaveBob(LaunchWaveSpec.PERIOD_MS / 4f, amplitude)
        val sink = launchWaveBob(LaunchWaveSpec.PERIOD_MS * 3f / 4f, amplitude)
        val settled = launchWaveBob(LaunchWaveSpec.TOTAL_MS, amplitude)

        assertTrue(rise.offset < 0f, "first quarter period lifts the element")
        assertTrue(rise.scale > 1f, "a lifted element is slightly larger")
        assertTrue(sink.offset > 0f, "third quarter period sinks it below rest")
        assertTrue(abs(sink.offset) < abs(rise.offset), "each swing is smaller than the last")
        assertTrue(abs(settled.offset) < 0.5f, "the clock stops below half a pixel")
    }

    @Test
    fun the_swing_overshoots_once_and_stays_under_eight_dp() {
        // 3× density: the amplitude in px, and half a pixel as the edge of visibility.
        val amplitude = LaunchWaveSpec.AMPLITUDE_DP * 3f
        val peak = (1..LaunchWaveSpec.PERIOD_MS.toInt()).maxOf { -launchWaveBob(it.toFloat(), amplitude).offset }
        assertTrue(peak <= 8f * 3f, "the highest rise is under 8dp")
        val secondRise = launchWaveBob(LaunchWaveSpec.PERIOD_MS * 5f / 4f, amplitude)
        assertTrue(abs(secondRise.offset) < 0.5f, "after the one dip below rest nothing visible moves")
    }

    @Test
    fun the_wave_crosses_one_window_top_to_bottom() {
        assertEquals(0f, launchWaveArrivalMs(topPx = 0f, windowPx = 2000f))
        assertEquals(LaunchWaveSpec.TRAVEL_MS / 2f, launchWaveArrivalMs(topPx = 1000f, windowPx = 2000f))
        // Rows below the fold arrive with the last visible row rather than after the clock stops.
        assertEquals(LaunchWaveSpec.TRAVEL_MS, launchWaveArrivalMs(topPx = 5000f, windowPx = 2000f))
        assertEquals(0f, launchWaveArrivalMs(topPx = -40f, windowPx = 2000f))
    }

    @Test
    fun the_gate_plays_once_per_arming() {
        assertFalse(LaunchWaveGate.pending)
        LaunchWaveGate.arm()
        assertTrue(LaunchWaveGate.pending)
        assertTrue(LaunchWaveGate.consume())
        assertFalse(LaunchWaveGate.consume())
    }

    @Test
    fun leaving_the_library_disarms_the_wave() {
        LaunchWaveGate.arm()
        LaunchWaveGate.disarm()
        assertFalse(LaunchWaveGate.consume())
    }
}
