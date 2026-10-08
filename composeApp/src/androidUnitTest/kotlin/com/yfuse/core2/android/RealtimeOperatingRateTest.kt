package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealtimeOperatingRateTest {
    @Test
    fun the_decoder_is_asked_for_the_rate_the_playback_speed_needs() {
        assertEquals(24f, realtimeOperatingRate(24f, 1f))
        assertEquals(48f, realtimeOperatingRate(24f, 2f))
        assertEquals(29.97f * 1.5f, realtimeOperatingRate(29.97f, 1.5f))
    }

    @Test
    fun an_unknown_frame_rate_or_speed_sets_no_operating_rate() {
        assertNull(realtimeOperatingRate(0f, 1f))
        assertNull(realtimeOperatingRate(Float.NaN, 1f))
        assertNull(realtimeOperatingRate(24f, 0f))
        assertNull(realtimeOperatingRate(24f, Float.POSITIVE_INFINITY))
    }
}
