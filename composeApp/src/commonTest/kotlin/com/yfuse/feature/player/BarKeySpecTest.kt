package com.yfuse.feature.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarKeySpecTest {
    @Test
    fun secondary_keys_stay_compact_wherever_the_row_has_room() {
        val sideways = barKeySpec(width = 740.dp, compact = false, largeText = false)

        assertEquals(28.dp, sideways.ring)
        assertEquals(16.dp, sideways.icon)
        assertFalse(sideways.labels)
    }

    @Test
    fun a_narrow_sideways_phone_steps_down_instead_of_losing_a_key() {
        assertEquals(28.dp, barKeySpec(width = 640.dp, compact = false, largeText = false).ring)
        assertEquals(26.dp, barKeySpec(width = 550.dp, compact = false, largeText = false).ring)
    }

    @Test
    fun large_text_gets_larger_keys_where_they_fit() {
        assertEquals(30.dp, barKeySpec(width = 740.dp, compact = false, largeText = true).ring)
        assertEquals(18.dp, barKeySpec(width = 740.dp, compact = false, largeText = true).icon)
        assertEquals(28.dp, barKeySpec(width = 300.dp, compact = true, largeText = true).ring)
    }

    @Test
    fun only_a_wide_sideways_bar_names_its_keys() {
        assertTrue(barKeySpec(width = 900.dp, compact = false, largeText = false).labels)
        assertFalse(barKeySpec(width = 900.dp, compact = true, largeText = false).labels)
    }
}
