package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackEndsAtTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    /** UTC+8, 24-hour: the epoch reads 08:00. */
    private val beijing = PlayerWallClock(readAtEpochMs = 0L, utcOffsetMs = 8 * hour, use24Hour = true)

    @Test
    fun atNormalSpeedTheFileEndsWhenItsRemainingTimeHasPassed() {
        assertEquals("结束于 08:45", playbackEndsAtLabel(0L, 45 * minute, 1f, 0L, beijing))
        assertEquals("结束于 08:30", playbackEndsAtLabel(15 * minute, 45 * minute, 1f, 0L, beijing))
    }

    @Test
    fun theRemainingTimeIsDividedByTheSpeed() {
        assertEquals("结束于 08:30", playbackEndsAtLabel(0L, hour, 2f, 0L, beijing))
        assertEquals("结束于 08:40", playbackEndsAtLabel(0L, hour, 1.5f, 0L, beijing))
        assertEquals("结束于 10:00", playbackEndsAtLabel(0L, hour, 0.5f, 0L, beijing))
    }

    @Test
    fun theClockDropsSecondsAndWrapsPastMidnight() {
        assertEquals("结束于 08:00", playbackEndsAtLabel(0L, 59_000L, 1f, 0L, beijing))
        val lateNight = 15 * hour + 50 * minute // 23:50 in UTC+8
        assertEquals("结束于 00:20", playbackEndsAtLabel(0L, 30 * minute, 1f, lateNight, beijing))
    }

    @Test
    fun aTwelveHourDeviceGetsItsOwnClock() {
        val twelve = beijing.copy(use24Hour = false)
        assertEquals("结束于 上午8:45", playbackEndsAtLabel(0L, 45 * minute, 1f, 0L, twelve))
        assertEquals("结束于 下午9:47", playbackEndsAtLabel(0L, 13 * hour + 47 * minute, 1f, 0L, twelve))
        assertEquals("下午12:05", wallClockLabel(12 * hour + 5 * minute, use24Hour = false))
        assertEquals("上午12:05", wallClockLabel(5 * minute, use24Hour = false))
    }

    @Test
    fun zonesBehindUtcCountBackFromTheEpoch() {
        val newYork = PlayerWallClock(readAtEpochMs = 0L, utcOffsetMs = -5 * hour, use24Hour = true)
        assertEquals("结束于 19:10", playbackEndsAtLabel(0L, 10 * minute, 1f, 0L, newYork))
        assertEquals("23:59", wallClockLabel(-1L, use24Hour = true))
    }

    @Test
    fun nothingIsSaidWithoutADurationATimeLeftOrASpeed() {
        assertNull(playbackEndsAtLabel(0L, 0L, 1f, 0L, beijing))
        assertNull(playbackEndsAtLabel(45 * minute, 45 * minute, 1f, 0L, beijing))
        assertNull(playbackEndsAtLabel(50 * minute, 45 * minute, 1f, 0L, beijing))
        assertNull(playbackEndsAtLabel(0L, 45 * minute, 0f, 0L, beijing))
        assertNull(playbackEndsAtLabel(0L, 45 * minute, Float.NaN, 0L, beijing))
    }
}
