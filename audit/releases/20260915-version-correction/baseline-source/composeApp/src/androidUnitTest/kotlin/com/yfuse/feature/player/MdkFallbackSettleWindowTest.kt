package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkFallbackSettleWindowTest {
    @Test
    fun progressive_load_gets_a_fresh_window_after_slow_encoder_cleanup() {
        val window = FallbackSettleWindow(requiredPolls = 3)

        // The first invalid direct stream may fall back immediately.
        assertTrue(window.ready)
        window.restart()

        // Polls continue while DELETE /Videos/ActiveEncodings is in flight.
        repeat(5) { window.tick() }
        assertTrue(window.ready)

        // Once the progressive URL is actually handed to MDK, none of those cleanup-time
        // polls count against it.
        window.restart()
        assertFalse(window.ready)
        repeat(2) { window.tick() }
        assertFalse(window.ready)
        window.tick()
        assertTrue(window.ready)
    }
}
