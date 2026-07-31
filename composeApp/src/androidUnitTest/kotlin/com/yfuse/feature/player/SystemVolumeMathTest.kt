package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemVolumeMathTest {
    @Test
    fun fractionUsesTheActualStreamRange() {
        assertEquals(0f, streamVolumeFraction(current = 2, min = 2, max = 12))
        assertEquals(0.5f, streamVolumeFraction(current = 7, min = 2, max = 12))
        assertEquals(1f, streamVolumeFraction(current = 12, min = 2, max = 12))
    }

    @Test
    fun requestedVolumeRoundsAndClampsToTheStreamRange() {
        assertEquals(2, streamVolumeForFraction(fraction = -1f, min = 2, max = 12))
        assertEquals(7, streamVolumeForFraction(fraction = 0.5f, min = 2, max = 12))
        assertEquals(12, streamVolumeForFraction(fraction = 2f, min = 2, max = 12))
    }
}
