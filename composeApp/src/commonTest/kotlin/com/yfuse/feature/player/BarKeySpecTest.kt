package com.yfuse.feature.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarKeySpecTest {
    @Test
    fun keys_are_drawn_large_enough_to_read_wherever_the_row_has_room() {
        val sideways = barKeySpec(width = 740.dp, compact = false, largeText = false)

        assertEquals(32.dp, sideways.ring)
        assertEquals(18.dp, sideways.icon)
        assertFalse(sideways.labels)
    }

    @Test
    fun a_narrow_sideways_phone_steps_down_instead_of_losing_a_key() {
        // 640 dp holds 32 dp rings; 600 dp does not and gets 30 dp ones.
        assertEquals(32.dp, barKeySpec(width = 640.dp, compact = false, largeText = false).ring)
        assertEquals(30.dp, barKeySpec(width = 600.dp, compact = false, largeText = false).ring)
    }

    @Test
    fun large_text_gets_larger_keys_where_they_fit() {
        assertEquals(36.dp, barKeySpec(width = 740.dp, compact = false, largeText = true).ring)
        assertEquals(20.dp, barKeySpec(width = 740.dp, compact = false, largeText = true).icon)
        // An upright phone's key row is narrower: 36 dp would push 选集 off it.
        assertEquals(32.dp, barKeySpec(width = 332.dp, compact = true, largeText = true).ring)
    }

    @Test
    fun only_a_wide_sideways_bar_names_its_keys() {
        assertTrue(barKeySpec(width = 900.dp, compact = false, largeText = false).labels)
        assertFalse(barKeySpec(width = 900.dp, compact = true, largeText = false).labels)
    }
}
