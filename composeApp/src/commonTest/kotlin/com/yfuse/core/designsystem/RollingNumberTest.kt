package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollingNumberTest {
    @Test
    fun columns_line_up_on_the_units_and_only_changed_digits_roll() {
        assertEquals(
            listOf(
                RollingSlot('4', '4', rolls = false, order = 0),
                RollingSlot('5', '7', rolls = true, order = 0),
                RollingSlot('%', '%', rolls = false, order = 0),
            ),
            rollingSlots("45%", "47%"),
        )
        // A point stays put; an unchanged figure rolls nothing at all.
        assertEquals(listOf(false, false, true), rollingSlots("8.4", "8.5").map { it.rolls })
        assertTrue(rollingSlots("8.4", "8.4").none { it.rolls })
    }

    @Test
    fun a_new_leading_digit_rolls_in_after_the_units() {
        assertEquals(
            listOf(
                RollingSlot(null, '1', rolls = true, order = 1),
                RollingSlot('9', '0', rolls = true, order = 0),
                RollingSlot('%', '%', rolls = false, order = 0),
            ),
            rollingSlots("9%", "10%"),
        )
        // A leading digit going away is simply dropped; the rest still meet on the right.
        assertEquals("99%", rollingSlots("100%", "99%").map { it.current }.joinToString(""))
        assertEquals(listOf<Char?>('0', '0', '%'), rollingSlots("100%", "99%").map { it.previous })
    }

    @Test
    fun rising_numbers_come_up_from_below_and_falling_ones_drop_from_above() {
        assertEquals(1, rollingDirection("9%", "10%"))
        assertEquals(-1, rollingDirection("100%", "99%"))
        assertEquals(-1, rollingDirection("★ 8.4", "★ 8.1"))
        // Nothing to compare: the default, from below.
        assertEquals(1, rollingDirection("—", "8.1"))
    }

    @Test
    fun neighbouring_digits_start_thirty_milliseconds_apart_and_all_land() {
        val total = ROLLING_DIGIT_MS + ROLLING_DIGIT_STAGGER_MS
        val atStagger = ROLLING_DIGIT_STAGGER_MS.toFloat() / total
        // When the units have had their 30 ms, the tens have not moved yet.
        assertTrue(rollingSlotProgress(atStagger, order = 0, totalMs = total, calm = false) > 0f)
        assertEquals(0f, rollingSlotProgress(atStagger, order = 1, totalMs = total, calm = false))
        assertEquals(1f, rollingSlotProgress(1f, order = 0, totalMs = total, calm = false), 0.001f)
        assertEquals(1f, rollingSlotProgress(1f, order = 1, totalMs = total, calm = false), 0.001f)
        // 静息 fades every changed digit together.
        assertEquals(0.5f, rollingSlotProgress(0.5f, order = 3, totalMs = ROLLING_CALM_MS, calm = true), 0.001f)
    }

    @Test
    fun a_fast_source_is_let_through_at_most_ten_times_a_second() {
        assertEquals(0L, throttleWaitMillis(null, ROLLING_NUMBER_MIN_INTERVAL_MS))
        assertEquals(70L, throttleWaitMillis(30L, ROLLING_NUMBER_MIN_INTERVAL_MS))
        assertEquals(0L, throttleWaitMillis(150L, ROLLING_NUMBER_MIN_INTERVAL_MS))
    }
}
