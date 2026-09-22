package com.yfuse.core2.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class YSubtitleClockAnchorTest {
    @Test
    fun observed_positions_advance_with_speed_and_freeze_when_publication_stalls() {
        val anchor = YSubtitleClockAnchor(1_000, 5_000_000_000, advancing = true, speed = 2f)
        assertEquals(1_200L, anchor.positionAt(5_100_000_000))
        assertEquals(1_500L, anchor.positionAt(8_000_000_000))
        assertEquals(1_000L, anchor.positionAt(4_000_000_000))
    }

    @Test
    fun pause_buffering_and_seek_rebase_the_clock_without_old_animation_drift() {
        val paused = YSubtitleClockAnchor(2_000, 1_000_000_000, advancing = false, speed = 1f)
        assertEquals(2_000L, paused.positionAt(1_100_000_000))
        val sought = YSubtitleClockAnchor(500, 1_100_000_000, advancing = true, speed = 1f)
        assertEquals(550L, sought.positionAt(1_150_000_000))
    }
}
