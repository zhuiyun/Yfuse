package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioControlPolicyTest {
    @Test
    fun `automatic sync cancels the measured video offset`() {
        assertEquals(-120L, calibratedAudioDelayMs(0L, 120L))
        assertEquals(80L, calibratedAudioDelayMs(200L, 120L))
    }

    @Test
    fun `automatic sync remains inside the user safe range`() {
        assertEquals(-2_000L, calibratedAudioDelayMs(0L, 9_000L))
        assertEquals(2_000L, calibratedAudioDelayMs(0L, -9_000L))
    }
}
