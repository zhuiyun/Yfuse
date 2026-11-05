package com.yfuse.feature.player

import com.mplaykit.`Playback$Progress`
import com.mplaykit.`Playback$State`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SenPlayerSnapshotTest {
    @Test
    fun seekable_flag_and_cache_duration_are_not_buffering_or_playback_speed() {
        val flags =
            LongArray(22).apply {
                this[0] = 7L
                this[6] = 1L
                this[8] = 1920L
                this[9] = 1080L
            }
        val state =
            `Playback$State`(
                flags,
                doubleArrayOf(10.0, 100.0, 25.0),
                Array(7) {
                    if (it ==
                        0
                    ) {
                        "playing"
                    } else {
                        ""
                    }
                },
            )
        assertFalse(state.buffering)
        assertEquals(25.0, state.cacheDuration)
        assertEquals(1920, state.width)
        assertEquals(1080, state.height)
    }

    @Test
    fun cache_snapshot_owns_its_range_array_and_preserves_holes() {
        val ranges = doubleArrayOf(0.0, 10.0, 30.0, 40.0)
        val progress =
            `Playback$Progress`(
                longArrayOf(7, 0, 0),
                doubleArrayOf(5.0, 100.0, 0.0, 5.0, 10.0, 24.0),
                ranges,
                arrayOf("", ""),
            )
        ranges[2] = 10.0
        assertEquals(listOf(0.0, 10.0, 30.0, 40.0), progress.ranges.toList())
        assertEquals(10.0, progress.bufferedEnd)
    }

    @Test
    fun unknown_abi_and_nonfinite_native_time_do_not_leak_invalid_state() {
        assertFailsWith<IllegalArgumentException> { `Playback$State`(longArrayOf(1), doubleArrayOf(), arrayOf()) }
        assertEquals(0L, secondsToMs(Double.NaN))
        assertEquals(0L, secondsToMs(Double.POSITIVE_INFINITY))
        assertEquals(Long.MAX_VALUE, secondsToMs(Double.MAX_VALUE))
    }
}
