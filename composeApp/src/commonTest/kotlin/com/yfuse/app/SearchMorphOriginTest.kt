package com.yfuse.app

import androidx.compose.ui.geometry.Rect
import com.yfuse.core.designsystem.SearchMorphOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SearchMorphOriginTest {
    private val key = Rect(330f, 760f, 392f, 822f)
    private val clock = HandClock()
    private val origin = SearchMorphOrigin(clock).apply { bounds = key }

    @Test
    fun the_field_that_opens_straight_after_the_tap_morphs_from_the_dock_once() {
        origin.begin()
        clock.now += 120.milliseconds

        assertEquals(key, origin.consume())
        assertNull(origin.consume())
    }

    @Test
    fun a_tap_that_no_field_took_up_is_not_played_on_a_later_arrival() {
        // 搜索 opened on a detail left on its stack: there was no field to take the morph.
        origin.begin()
        clock.now += 40.seconds

        assertNull(origin.consume())
    }

    @Test
    fun a_fresh_tap_grants_its_own_morph_after_a_stale_one() {
        origin.begin()
        clock.now += 40.seconds
        origin.begin()
        clock.now += 80.milliseconds

        assertEquals(key, origin.consume())
    }

    /** Time that passes only when the test says so. */
    private class HandClock : TimeSource {
        var now = Duration.ZERO

        override fun markNow(): TimeMark {
            val at = now
            return object : TimeMark {
                override fun elapsedNow(): Duration = now - at
            }
        }
    }
}
