package com.yfuse.core.data.dto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeMinutesTest {
    @Test
    fun a_runtime_under_a_minute_is_one_minute() {
        assertEquals(1, runtimeMinutesOf(400_000_000L))
        assertEquals(1, runtimeMinutesOf(1L))
    }

    @Test
    fun longer_runtimes_keep_their_whole_minutes() {
        assertEquals(46, runtimeMinutesOf(28_063_680_000L))
        assertEquals(1, runtimeMinutesOf(1_190_000_000L))
    }

    @Test
    fun no_runtime_is_no_minutes() {
        assertNull(runtimeMinutesOf(null))
        assertNull(runtimeMinutesOf(0L))
    }
}
