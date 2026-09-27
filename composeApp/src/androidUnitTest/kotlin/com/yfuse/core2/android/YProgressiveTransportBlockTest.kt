package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YProgressiveTransportBlockTest {
    @Test
    fun partial_response_exposes_only_received_bytes_at_the_requested_offset() {
        val pending = YProgressiveTransportBlock()
        val network = ByteArray(64) { it.toByte() }
        assertNull(pending.read(0, 8))
        pending.publish(network, 16, 128L)
        val slice = requireNotNull(pending.read(12, 16))
        assertContentEquals(byteArrayOf(12, 13, 14, 15), slice.bytes)
        assertEquals(12, slice.offset)
        assertEquals(128L, slice.contentLength)
        assertNull(pending.read(16, 16))
        pending.publish(network, 32, 128L)
        assertContentEquals(network.copyOfRange(16, 32), requireNotNull(pending.read(16, 32)).bytes)
    }

    @Test
    fun retry_does_not_change_bytes_already_delivered_to_the_demuxer() {
        val pending = YProgressiveTransportBlock()
        pending.publish(byteArrayOf(1, 2, 3, 4), 3, 4L)
        val delivered = requireNotNull(pending.read(0, 4))
        pending.clear()
        assertNull(pending.read(0, 4))
        pending.publish(byteArrayOf(5, 6, 7, 8), 2, 4L)
        assertContentEquals(byteArrayOf(1, 2, 3), delivered.bytes)
        assertContentEquals(byteArrayOf(5, 6), requireNotNull(pending.read(0, 4)).bytes)
        assertNull(pending.read(2, 1))
    }
}
