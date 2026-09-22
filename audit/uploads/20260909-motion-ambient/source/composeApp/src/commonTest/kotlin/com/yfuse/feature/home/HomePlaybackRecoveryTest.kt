package com.yfuse.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomePlaybackRecoveryTest {
    @Test
    fun recovery_position_is_converted_from_milliseconds_to_emby_ticks() {
        assertEquals(1_234_0000L, 1_234L.toEmbyTicks())
        assertEquals(0L, (-1L).toEmbyTicks())
    }

    @Test
    fun recovery_position_conversion_does_not_overflow() {
        assertEquals(Long.MAX_VALUE / 10_000L * 10_000L, Long.MAX_VALUE.toEmbyTicks())
    }
}
