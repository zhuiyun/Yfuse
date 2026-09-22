package com.yfuse.core2.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YAvSyncTest {
    @Test
    fun `measures video against extrapolated audio master`() {
        val offset =
            YAvSync.offsetUs(
                videoPresentationTimeUs = 1_025_000L,
                videoRenderedRealtimeNs = 2_010_000_000L,
                master = YClockSnapshot(positionUs = 1_000_000L, realtimeNs = 2_000_000_000L),
                speed = 1.5f,
            )

        assertEquals(10_000L, offset)
    }

    @Test
    fun `rejects invalid playback speed`() {
        assertFailsWith<IllegalArgumentException> {
            YAvSync.offsetUs(0L, 0L, YClockSnapshot(0L, 0L), speed = 0f)
        }
    }
}
