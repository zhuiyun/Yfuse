package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTunnelSeekAudioTest {
    @Test
    fun `seek trims whole PCM frames before the requested audio timestamp`() {
        assertEquals(1_920, tunnelSeekAudioSkipBytes(0L, 10_000L, 48_000, 4, 4_800))
        assertEquals(4, tunnelSeekAudioSkipBytes(0L, 1L, 48_000, 4, 4_800))
        assertEquals(4_800, tunnelSeekAudioSkipBytes(0L, 1_000_000L, 48_000, 4, 4_800))
        assertEquals(0, tunnelSeekAudioSkipBytes(10_000L, 10_000L, 48_000, 4, 4_800))
    }
}
