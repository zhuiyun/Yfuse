package com.yfuse.core2.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YFrameRatePolicyTest {
    @Test
    fun `fractional cinema cadence is never rounded away`() {
        assertEquals(23.976f, videoFrameRateHint(23.976f)?.framesPerSecond)
        assertEquals(29.97f, videoFrameRateHint(29.97f)?.framesPerSecond)
        assertEquals(59.94f, videoFrameRateHint(59.94f)?.framesPerSecond)
    }

    @Test
    fun `invalid or non-video rates do not reach platform display APIs`() {
        assertNull(videoFrameRateHint(0f))
        assertNull(videoFrameRateHint(Float.NaN))
        assertNull(videoFrameRateHint(300f))
    }
}
