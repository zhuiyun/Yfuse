package com.yfuse.core2.android

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class PcmWriteCursorTest {
    @Test
    fun aLongBufferRetainsItsOriginAcrossBackpressureAndSinkRebuild() {
        val cursor = PcmWriteCursor()
        val data = ByteBuffer.allocate(100_000).apply { position(2_000) }
        assertEquals(10_000_000L, cursor.positionUs(data, 10_000_000, 4, 48_000))
        cursor.consumed(data) // A zero write must keep the same origin.
        data.position(50_000)
        cursor.consumed(data)
        assertEquals(10_250_000L, cursor.positionUs(data, 10_000_000, 4, 48_000))
        // A replacement sink reuses the cursor; its first frame is the remaining frame, not the old PTS.
        assertEquals(10_250_000L, cursor.positionUs(data, 10_000_000, 4, 48_000))
        data.position(data.limit())
        cursor.consumed(data)
        data.clear()
        assertEquals(11_000_000L, cursor.positionUs(data, 11_000_000, 4, 48_000))
    }
}
