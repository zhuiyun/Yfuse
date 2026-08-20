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

    @Test
    fun `selects highest exact fractional cadence multiple`() {
        val selected =
            selectDisplayRefreshTarget(
                hint = YFrameRateHint(23.976f),
                supportedRefreshRates = listOf(60f, 119.88f, 120f, 47.952f),
            )

        assertEquals(119.88f, selected?.refreshRate)
        assertEquals(5, selected?.cadenceMultiplier)
    }

    @Test
    fun `keeps current exact cadence to avoid an unnecessary black screen`() {
        val selected =
            selectDisplayRefreshTarget(
                hint = YFrameRateHint(24f),
                supportedRefreshRates = listOf(24f, 48f, 120f),
                currentRefreshRate = 48f,
            )

        assertEquals(48f, selected?.refreshRate)
    }

    @Test
    fun `variable sources never force a fixed display mode`() {
        assertNull(
            selectDisplayRefreshTarget(
                hint = YFrameRateHint(59.94f, fixedSource = false, variableSource = true),
                supportedRefreshRates = listOf(60f, 120f),
            ),
        )
    }
}
