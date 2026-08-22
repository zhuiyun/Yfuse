package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailActionFormattingTest {
    @Test
    fun noProgress_hasNoResumeClock() {
        assertNull(formatResumePosition(0L))
    }

    @Test
    fun subHourProgress_usesMinuteSecondClock() {
        assertEquals("20:01", formatResumePosition(1_201L * 10_000_000L))
    }

    @Test
    fun hourProgress_usesHourMinuteSecondClock() {
        assertEquals("1:02:03", formatResumePosition(3_723L * 10_000_000L))
    }
}
