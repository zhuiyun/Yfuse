package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class OffsetStepperTest {
    @Test
    fun `a step moves the absolute offset and stops at the limit`() {
        assertEquals(100L, steppedOffsetMs(currentMs = 0L, stepMs = 100L, limitMs = 60_000L))
        assertEquals(-400L, steppedOffsetMs(currentMs = 100L, stepMs = -500L, limitMs = 60_000L))
        assertEquals(60_000L, steppedOffsetMs(currentMs = 59_800L, stepMs = 500L, limitMs = 60_000L))
        assertEquals(-2_000L, steppedOffsetMs(currentMs = -1_900L, stepMs = -200L, limitMs = 2_000L))
    }

    @Test
    fun `seconds read the way the steps move them`() {
        assertEquals("0", offsetSecondsLabel(0L))
        assertEquals("0.3", offsetSecondsLabel(300L))
        assertEquals("1.5", offsetSecondsLabel(-1_500L))
        assertEquals("2", offsetSecondsLabel(2_000L))
        assertEquals("0.25", offsetSecondsLabel(250L))
    }

    @Test
    fun `the current value says which way the subtitle or the sound moved`() {
        assertEquals("同步", subtitleOffsetLabel(0L))
        assertEquals("提前 0.3 秒", subtitleOffsetLabel(-300L))
        assertEquals("延后 1.5 秒", subtitleOffsetLabel(1_500L))
        assertEquals("同步", audioDelayLabel(0L))
        assertEquals("提前 150 毫秒", audioDelayLabel(-150L))
        assertEquals("延后 200 毫秒", audioDelayLabel(200L))
    }

    @Test
    fun `step labels carry their sign`() {
        assertEquals("−0.5 秒", subtitleOffsetStepLabel(-500L))
        assertEquals("+0.1 秒", subtitleOffsetStepLabel(100L))
        assertEquals("−200", audioDelayStepLabel(-200L))
        assertEquals("+50", audioDelayStepLabel(50L))
    }
}
