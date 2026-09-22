package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class BufferedPositionTest {
    @Test
    fun convertsForwardBufferToAbsolutePosition() {
        assertEquals(42_000L, bufferedEndPositionMs(30_000L, 120_000L, 12_000L))
    }

    @Test
    fun clampsBufferToKnownDuration() {
        assertEquals(120_000L, bufferedEndPositionMs(115_000L, 120_000L, 12_000L))
    }

    @Test
    fun ignoresNegativeValuesAndSaturatesOverflow() {
        assertEquals(0L, bufferedEndPositionMs(-1L, 0L, -1L))
        assertEquals(Long.MAX_VALUE, bufferedEndPositionMs(Long.MAX_VALUE - 1L, 0L, 10L))
    }
}
