package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Hdr10PlusFrameMetadataTest {
    @Test
    fun late_metadata_is_bound_to_its_picture_even_when_decode_order_differs() {
        val timeline = Hdr10PlusFrameMetadata()
        timeline.queue(0L, null)
        timeline.queue(80_000L, payload)
        timeline.queue(40_000L, null)
        assertNull(timeline.take(0L))
        assertNull(timeline.take(40_000L))
        assertEquals(3000f, assertNotNull(timeline.take(80_000L)).scenePeakNits)
        assertNull(timeline.take(120_000L))
    }

    @Test
    fun seek_invalid_metadata_and_eviction_fall_back_to_static_color() {
        val timeline = Hdr10PlusFrameMetadata(2)
        timeline.queue(0L, payload)
        timeline.queue(1L, payload)
        timeline.queue(2L, payload)
        assertNull(timeline.take(0L))
        timeline.queue(1L, byteArrayOf(1))
        assertNull(timeline.take(1L))
        timeline.clear()
        assertNull(timeline.take(2L))
    }

    private val payload =
        "b5003c0001040040001f404e204e203a9804e2000000"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
