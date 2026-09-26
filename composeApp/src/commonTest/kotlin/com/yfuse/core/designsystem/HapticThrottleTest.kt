package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HapticThrottleTest {
    @Test
    fun repeatingMarksPlayAtMostOncePerInterval() {
        assertFalse(hapticAllowed(HapticSignal.Tick, sinceLastMs = 10L))
        assertFalse(hapticAllowed(HapticSignal.FrequentTick, sinceLastMs = HAPTIC_TICK_INTERVAL_MS - 1))
        assertTrue(hapticAllowed(HapticSignal.Tick, sinceLastMs = HAPTIC_TICK_INTERVAL_MS))
    }

    @Test
    fun oneOffSignalsAreNeverSwallowedByATick() {
        HapticSignal.entries.filterNot { it.repeats() }.forEach { signal ->
            assertTrue(hapticAllowed(signal, sinceLastMs = 0L), signal.name)
        }
    }
}
