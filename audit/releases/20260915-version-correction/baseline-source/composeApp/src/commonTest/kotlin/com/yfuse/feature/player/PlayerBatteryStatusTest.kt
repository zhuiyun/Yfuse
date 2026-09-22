package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerBatteryStatusTest {
    @Test
    fun absent_or_invalid_battery_data_is_not_displayed_as_zero() {
        assertNull(playerBatteryPercent(-1, 100))
        assertNull(playerBatteryPercent(50, -1))
        assertNull(playerBatteryPercent(50, 0))
    }

    @Test
    fun non_percentage_scales_and_out_of_range_levels_are_bounded_without_overflow() {
        assertEquals(75, playerBatteryPercent(150, 200))
        assertEquals(0, playerBatteryPercent(0, 100))
        assertEquals(100, playerBatteryPercent(101, 100))
        assertEquals(100, playerBatteryPercent(Int.MAX_VALUE, 1))
    }
}
