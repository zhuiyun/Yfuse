package com.yfuse.feature.handoff

import com.yfuse.watch.protocol.RemoteControlKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteSwipeTest {
    private fun swipe() = RemoteSwipe(slop = 10f, extent = 100f, repeatDelayMs = 400L, repeatIntervalMs = 150L)

    @Test
    fun a_touch_that_never_travels_is_ok() {
        assertEquals(RemoteControlKey.Center, swipe().release(0f, 0f))
        val wobble = swipe()
        assertNull(wobble.move(3f, 4f, nowMs = 10L))
        assertEquals(RemoteControlKey.Center, wobble.release(0f, 0f))
    }

    @Test
    fun a_swipe_sends_one_direction_once_it_has_come_far_enough() {
        val right = swipe()
        assertNull(right.move(30f, 2f, nowMs = 10L))
        assertEquals(RemoteControlKey.Right, right.move(100f, 5f, nowMs = 20L))
        assertNull(right.move(140f, 5f, nowMs = 30L))
        assertNull(right.release(0f, 0f), "the swipe already sent its direction")
        // A slightly diagonal drag keeps to the axis it leans towards.
        val up = swipe()
        assertNull(up.move(40f, -45f, nowMs = 0L))
        assertEquals(RemoteControlKey.Up, up.move(60f, -110f, nowMs = 10L))
    }

    @Test
    fun held_past_the_edge_it_repeats_until_pulled_back() {
        val down = swipe()
        assertEquals(RemoteControlKey.Down, down.move(0f, 120f, nowMs = 0L))
        assertNull(down.hold(399L))
        assertEquals(RemoteControlKey.Down, down.hold(400L))
        assertNull(down.hold(500L))
        assertEquals(RemoteControlKey.Down, down.hold(550L))
        assertNull(down.move(0f, 60f, nowMs = 600L), "pulled back inside the edge, it stops")
        assertNull(down.hold(2_000L))
        assertNull(down.move(0f, 130f, nowMs = 2_100L), "out again, it waits the delay again")
        assertEquals(RemoteControlKey.Down, down.hold(2_500L))
        assertNull(down.move(0f, -130f, nowMs = 2_600L), "one direction per swipe")
        assertNull(down.hold(5_000L))
    }

    @Test
    fun a_flick_that_would_carry_far_enough_sends_on_release() {
        val flick = swipe()
        assertNull(flick.move(-40f, 0f, nowMs = 0L))
        assertEquals(RemoteControlKey.Left, flick.release(vx = -600f, vy = 0f))
        val slow = swipe()
        assertNull(slow.move(0f, -40f, nowMs = 0L))
        assertNull(slow.release(vx = 0f, vy = -100f))
    }
}
