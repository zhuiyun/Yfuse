package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class NextUpWindowTest {
    @Test
    fun a_short_episode_announces_its_next_one_over_a_twentieth_of_itself() {
        assertEquals(4_500L, nextUpWindowMs(90_000L))
        assertEquals(9_000L, nextUpWindowMs(180_000L))
    }

    @Test
    fun the_window_stays_between_three_and_ten_seconds() {
        assertEquals(3_000L, nextUpWindowMs(40_000L))
        assertEquals(10_000L, nextUpWindowMs(280_000L))
    }

    @Test
    fun a_full_length_or_unknown_episode_keeps_ten_seconds() {
        assertEquals(NEXT_UP_WINDOW_MS, nextUpWindowMs(1_500_000L))
        assertEquals(NEXT_UP_WINDOW_MS, nextUpWindowMs(0L))
    }
}
