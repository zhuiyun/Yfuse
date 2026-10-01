package com.yfuse.feature.player

import com.yfuse.core.sync.WatchStickerMotion
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStickerTrayTest {
    @Test
    fun a_preset_wraps_where_its_own_cycle_ends_and_nowhere_else() {
        // A 920 ms hop does not divide a minute: the tray's old one-minute loop restarted it mid-hop.
        val period = WatchStickerMotion.Bounce.periodMs
        assertEquals(
            stickerMotionPhase(59_999L, period) + 1f / period,
            stickerMotionPhase(60_000L, period),
            absoluteTolerance = 1e-4f,
        )
        assertEquals(0f, stickerMotionPhase(period * 66L, period))
        assertEquals(0.5f, stickerMotionPhase(period / 2L, period))
    }
}
