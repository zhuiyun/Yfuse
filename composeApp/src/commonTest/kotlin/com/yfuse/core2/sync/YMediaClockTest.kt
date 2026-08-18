package com.yfuse.core2.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class YMediaClockTest {
    @Test
    fun `one second of monotonic time advances one second at normal speed`() {
        val clock = YMediaClock()
        clock.start(positionUs = 5_000_000L, realtimeNs = 1_000_000_000L)

        assertEquals(6_000_000L, clock.positionUs(2_000_000_000L))
        assertEquals(2_000_000_000L, clock.presentationTimeNs(6_000_000L))
    }

    @Test
    fun `speed changes preserve the current media anchor`() {
        val clock = YMediaClock()
        clock.start(positionUs = 0L, realtimeNs = 0L)
        clock.setSpeed(
            speed = 2f,
            currentPositionUs = 1_000_000L,
            realtimeNs = 1_000_000_000L,
        )

        assertEquals(3_000_000L, clock.positionUs(2_000_000_000L))
        assertEquals(1_500_000_000L, clock.presentationTimeNs(2_000_000L))
    }

    @Test
    fun `paused clock does not advance`() {
        val clock = YMediaClock()
        clock.start(positionUs = 2_000_000L, realtimeNs = 1_000_000_000L)
        clock.pause(positionUs = 2_500_000L, realtimeNs = 1_500_000_000L)

        assertEquals(2_500_000L, clock.positionUs(10_000_000_000L))
    }
}
